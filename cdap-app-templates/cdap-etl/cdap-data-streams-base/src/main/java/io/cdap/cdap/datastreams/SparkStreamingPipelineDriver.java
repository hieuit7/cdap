/*
 * Copyright © 2016-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.datastreams;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.cdap.cdap.api.Admin;
import io.cdap.cdap.api.Transactionals;
import io.cdap.cdap.api.TxRunnable;
import io.cdap.cdap.api.data.DatasetContext;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.dataset.lib.FileSet;
import io.cdap.cdap.api.dataset.lib.FileSetProperties;
import io.cdap.cdap.api.macro.MacroEvaluator;
import io.cdap.cdap.api.spark.JavaSparkExecutionContext;
import io.cdap.cdap.api.spark.JavaSparkMain;
import io.cdap.cdap.etl.api.AlertPublisher;
import io.cdap.cdap.etl.api.ErrorTransform;
import io.cdap.cdap.etl.api.SplitterTransform;
import io.cdap.cdap.etl.api.Transform;
import io.cdap.cdap.etl.api.batch.BatchAggregator;
import io.cdap.cdap.etl.api.batch.BatchJoiner;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.batch.SparkCompute;
import io.cdap.cdap.etl.api.batch.SparkSink;
import io.cdap.cdap.etl.api.streaming.StreamingSource;
import io.cdap.cdap.etl.api.streaming.Windower;
import io.cdap.cdap.etl.common.Constants;
import io.cdap.cdap.etl.common.DefaultMacroEvaluator;
import io.cdap.cdap.etl.common.PhaseSpec;
import io.cdap.cdap.etl.common.PipelinePhase;
import io.cdap.cdap.etl.common.PipelineRuntime;
import io.cdap.cdap.etl.common.plugin.PipelinePluginContext;
import io.cdap.cdap.etl.proto.v2.spec.StageSpec;
import io.cdap.cdap.etl.spark.SparkPipelineRuntime;
import io.cdap.cdap.etl.spark.streaming.SparkStreamingPreparer;
import io.cdap.cdap.features.Feature;
import io.cdap.cdap.internal.io.SchemaTypeAdapter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function0;
import org.apache.spark.streaming.Checkpoint;
import org.apache.spark.streaming.CheckpointReader;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.StreamingContext;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.scheduler.ReceiverTracker;
import org.apache.twill.filesystem.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Driver for running pipelines using Spark Streaming.
 */
public class SparkStreamingPipelineDriver implements JavaSparkMain {
  private static final Logger LOG = LoggerFactory.getLogger(SparkStreamingPipelineDriver.class);
  private static final String DEFAULT_CHECKPOINT_DATASET_NAME = "defaultCheckpointDataset";
  private static final String SPARK_GRACEFUL_STOP_TIMEOUT = "spark.streaming.gracefulStopTimeout";

  // Overhead in milliseconds that Spark needs for graceful shutdown besides the job processing.
  // This helps to calculate a more accurate timeout for Spark gracefulStopTimeout
  private static final long GRACEFUL_SHUTDOWN_OVERHEAD = TimeUnit.MINUTES.toMillis(3L);

  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(Schema.class, new SchemaTypeAdapter())
    .create();
  private static final Set<String> SUPPORTED_PLUGIN_TYPES = ImmutableSet.of(
    StreamingSource.PLUGIN_TYPE, BatchSink.PLUGIN_TYPE, SparkSink.PLUGIN_TYPE, Transform.PLUGIN_TYPE,
    BatchAggregator.PLUGIN_TYPE, BatchJoiner.PLUGIN_TYPE, SparkCompute.PLUGIN_TYPE, Windower.PLUGIN_TYPE,
    ErrorTransform.PLUGIN_TYPE, SplitterTransform.PLUGIN_TYPE, AlertPublisher.PLUGIN_TYPE);

  @Override
  public void run(JavaSparkExecutionContext sec) throws Exception {
    DataStreamsPipelineSpec pipelineSpec = GSON.fromJson(sec.getSpecification().getProperty(Constants.PIPELINEID),
                                                         DataStreamsPipelineSpec.class);

    Set<StageSpec> stageSpecs = pipelineSpec.getStages();
    PipelinePhase pipelinePhase = PipelinePhase.builder(SUPPORTED_PLUGIN_TYPES)
      .addConnections(pipelineSpec.getConnections())
      .addStages(stageSpecs)
      .build();

    boolean checkpointsEnabled = pipelineSpec.getStateSpec().getMode() == DataStreamsStateSpec.Mode.SPARK_CHECKPOINTING;
    boolean isPreviewEnabled = pipelineSpec.isPreviewEnabled(sec);

    String checkpointDir = null;
    JavaSparkContext context = null;
    if (checkpointsEnabled && !isPreviewEnabled) {
      String pipelineName = sec.getApplicationSpecification().getName();
      String configCheckpointDir = pipelineSpec.getStateSpec().getCheckpointDir();
      if (Strings.isNullOrEmpty(configCheckpointDir)) {
        // Use the directory of a fileset dataset if the checkpoint directory is not set.
        Admin admin = sec.getAdmin();
        // TODO: CDAP-16329 figure out a way to filter out this fileset in dataset lineage
        if (!admin.datasetExists(DEFAULT_CHECKPOINT_DATASET_NAME)) {
          admin.createDataset(DEFAULT_CHECKPOINT_DATASET_NAME, FileSet.class.getName(),
                              FileSetProperties.builder().build());
        }
        // there isn't any way to instantiate the fileset except in a TxRunnable, so need to use a reference.
        AtomicReference<Location> checkpointBaseRef = new AtomicReference<>();
        Transactionals.execute(sec, new TxRunnable() {
          @Override
          public void run(DatasetContext context) throws Exception {
            FileSet checkpointFileSet = context.getDataset(DEFAULT_CHECKPOINT_DATASET_NAME);
            checkpointBaseRef.set(checkpointFileSet.getBaseLocation());
          }
        });
        configCheckpointDir = checkpointBaseRef.get().toURI().toString();
      }
      Path baseCheckpointDir = new Path(new Path(configCheckpointDir), pipelineName);
      Path checkpointDirPath = new Path(baseCheckpointDir, pipelineSpec.getPipelineId());
      checkpointDir = checkpointDirPath.toString();

      context = new JavaSparkContext();
      Configuration configuration = context.hadoopConfiguration();
      // Set the filesystem to whatever the checkpoint directory uses. This is necessary since spark will override
      // the URI schema with what is set in this config. This needs to happen before StreamingCompat.getOrCreate
      // is called, since StreamingCompat.getOrCreate will attempt to parse the checkpointDir before calling
      // context function.
      URI checkpointUri = checkpointDirPath.toUri();
      if (checkpointUri.getScheme() != null) {
        configuration.set("fs.defaultFS", checkpointDir);
      }
      FileSystem fileSystem = FileSystem.get(checkpointUri, configuration);

      // Checkpoint directory structure: [directory]/[pipelineName]/[pipelineId]
      // Ideally, when a pipeline is deleted, we would be able to delete [directory]/[pipelineName].
      // This is because we don't want another pipeline created with the same name to pick up the old checkpoint.
      // Since CDAP has no way to run application logic on deletion, we instead generate a unique pipeline id
      // and use that as the checkpoint directory as a subdirectory inside the pipeline name directory.
      // On start, we check for any other pipeline ids for that pipeline name, and delete them if they exist.
      if (!ensureDirExists(fileSystem, baseCheckpointDir)) {
        throw new IOException(
          String.format("Unable to create checkpoint base directory '%s' for the pipeline.", baseCheckpointDir));
      }

      try {
        for (FileStatus child : fileSystem.listStatus(baseCheckpointDir)) {
          if (child.isDirectory()) {
            if (!child.getPath().equals(checkpointDirPath) && !fileSystem.delete(child.getPath(), true)) {
              LOG.warn("Unable to delete checkpoint directory {} from an old pipeline.", child);
            }
          }
        }
      } catch (Exception e) {
        LOG.warn("Unable to clean up old checkpoint directories from old pipelines.", e);
      }

      if (!ensureDirExists(fileSystem, checkpointDirPath)) {
        throw new IOException(
          String.format("Unable to create checkpoint directory '%s' for the pipeline.", checkpointDir));
      }
    }

    JavaStreamingContext jssc = run(pipelineSpec, pipelinePhase, sec, checkpointDir, context);
    jssc.start();

    boolean stopped = false;
    try {
      // most programs will just keep running forever.
      // however, when CDAP stops the program, we get an interrupted exception.
      // at that point, we need to call stop on jssc, otherwise the program will hang and never stop.
      stopped = jssc.awaitTerminationOrTimeout(Long.MAX_VALUE);
    } catch (InterruptedException e) {
      // Catch the interrupted exception to clear the interrupted flag on the thread.
      // Interrupt is issued to break the await so that we can shutdown the program gracefully.
    } finally {
      if (!stopped) {
        long terminationTimeout = 0L;
        try {
          long terminationTime = sec.getTerminationTime();

          // By default, Spark set graceful stop timeout to be 10 * batch_interval.
          // However, time required for graceful shutdown should be closer to a batch_interval plus overhead,
          // assuming unprocessed data can be processed in an interval.
          terminationTimeout = terminationTime - System.currentTimeMillis() - GRACEFUL_SHUTDOWN_OVERHEAD;
          if (terminationTimeout >= pipelineSpec.getBatchIntervalMillis()) {
            LOG.debug("Setting spark stop timeout to {} ms", terminationTimeout);
            jssc.ssc().conf().set(SPARK_GRACEFUL_STOP_TIMEOUT, terminationTimeout + "ms");
          } else {
            // Skip graceful shutdown as there won't be enough time
            terminationTimeout = 0L;
          }

        } catch (IllegalStateException e) {
          // This shouldn't happen, but catch it in case there is future bug introduced.
          LOG.warn("Unexpected exception due to termination timeout is unavailable", e);
        }

        if (terminationTimeout <= 0 || !pipelineSpec.isStopGracefully()) {
          LOG.info("Terminate the streaming job immediately due to {}.",
                   pipelineSpec.isStopGracefully() ? "not enough time till termination" : "configuration");
          jssc.stop(true, false);
        } else {
          jssc.stop(true, true);

          // After stopping the streaming context, checks if all received data has been processed.
          if (pipelineSpec.getStateSpec().getMode() == DataStreamsStateSpec.Mode.SPARK_CHECKPOINTING
            && checkpointDir != null) {
            if (areAllBlocksProcessed(jssc, pipelineSpec, checkpointDir)) {
              LOG.info("All receiver blocks are processed in the checkpoint directory {}", checkpointDir);
              try {
                // If yes, we can clearup the checkpoint directory.
                if (Feature.STREAMING_PIPELINE_CHECKPOINT_DELETION.isEnabled(sec)) {
                  LOG.info("Deleting checkpoint directory {}", checkpointDir);
                  FileSystem fs = FileSystem.get(jssc.sparkContext().hadoopConfiguration());
                  fs.delete(new Path(checkpointDir), true);
                  LOG.info("Checkpoint directory {} deleted", checkpointDir);
                }
              } catch (Exception e) {
                LOG.warn("Failed to delete checkpoint directory {}", checkpointDir, e);
              }
            } else {
              LOG.info("There are unprocessed recevier records in the checkpoint directory {}", checkpointDir);
            }
          }
        }
      }
    }
  }

  private JavaStreamingContext run(DataStreamsPipelineSpec pipelineSpec,
                                   PipelinePhase pipelinePhase,
                                   JavaSparkExecutionContext sec,
                                   @Nullable String checkpointDir,
                                   @Nullable JavaSparkContext context) throws Exception {

    PipelinePluginContext pluginContext = new PipelinePluginContext(sec.getPluginContext(), sec.getMetrics(),
                                                                    pipelineSpec.isStageLoggingEnabled(),
                                                                    pipelineSpec.isProcessTimingEnabled());
    PipelineRuntime pipelineRuntime = new SparkPipelineRuntime(sec);
    MacroEvaluator evaluator = new DefaultMacroEvaluator(pipelineRuntime.getArguments(),
                                                         sec.getLogicalStartTime(),
                                                         sec.getSecureStore(),
                                                         sec.getServiceDiscoverer(),
                                                         sec.getNamespace());
    SparkStreamingPreparer preparer = new SparkStreamingPreparer(pluginContext, sec.getMetrics(), evaluator,
                                                                 pipelineRuntime, sec);
    try {
      SparkFieldLineageRecorder recorder = new SparkFieldLineageRecorder(sec, pipelinePhase, pipelineSpec, preparer);
      recorder.record();
    } catch (Exception e) {
      LOG.warn("Failed to emit field lineage operations for streaming pipeline", e);
    }
    Set<String> uncombinableSinks = preparer.getUncombinableSinks();

    // the content in the function might not run due to spark checkpointing, currently just have the lineage logic
    // before anything is run
    Function0<JavaStreamingContext> contextFunction = (Function0<JavaStreamingContext>) () -> {
      JavaSparkContext javaSparkContext = context == null ? new JavaSparkContext() : context;
      JavaStreamingContext jssc = new JavaStreamingContext(
        javaSparkContext, Durations.milliseconds(pipelineSpec.getBatchIntervalMillis()));
      boolean checkpointsDisabled = pipelineSpec.getStateSpec()
        .getMode() != DataStreamsStateSpec.Mode.SPARK_CHECKPOINTING;
      SparkStreamingPipelineRunner runner = new SparkStreamingPipelineRunner(sec, jssc, pipelineSpec,
                                                                             checkpointsDisabled);

      // TODO: figure out how to get partitions to use for aggregators and joiners.
      // Seems like they should be set at configure time instead of runtime? but that requires an API change.
      try {
        PhaseSpec phaseSpec = new PhaseSpec(sec.getApplicationSpecification().getName(), pipelinePhase,
                                            Collections.emptyMap(), pipelineSpec.isStageLoggingEnabled(),
                                            pipelineSpec.isProcessTimingEnabled());
        boolean shouldConsolidateStages = Boolean.parseBoolean(
          sec.getRuntimeArguments().getOrDefault(Constants.CONSOLIDATE_STAGES, Boolean.TRUE.toString()));
        boolean shouldCacheFunctions = Boolean.parseBoolean(
          sec.getRuntimeArguments().getOrDefault(Constants.CACHE_FUNCTIONS, Boolean.TRUE.toString()));
        runner.runPipeline(phaseSpec, StreamingSource.PLUGIN_TYPE, sec, Collections.emptyMap(),
                           pluginContext, Collections.emptyMap(), uncombinableSinks, shouldConsolidateStages,
                           shouldCacheFunctions);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      if (checkpointDir != null) {
        jssc.checkpoint(checkpointDir);
        jssc.sparkContext().hadoopConfiguration().set("fs.defaultFS", checkpointDir);
      }
      return jssc;
    };
    return checkpointDir == null
      ? contextFunction.call()
      : JavaStreamingContext.getOrCreate(checkpointDir, contextFunction, context.hadoopConfiguration());
  }

  private boolean ensureDirExists(FileSystem fileSystem, Path dir) throws IOException {
    return fileSystem.isDirectory(dir) || fileSystem.mkdirs(dir) || fileSystem.isDirectory(dir);
  }

  /**
   * Reports if all data blocks stored in the checkpoint generated by receivers are processed.
   *
   * @param jssc the spark streaming context for the streaming process
   * @param pipelineSpec the specification of the pipeline
   * @param checkpointDir the checkpoint directory configured for the pipeline
   * @return {@code true} if all data blocks are processed; {@code false} otherwise
   */
  private boolean areAllBlocksProcessed(JavaStreamingContext jssc,
                                        DataStreamsPipelineSpec pipelineSpec,
                                        String checkpointDir) {
    try {
      Option<Checkpoint> checkpointOption = CheckpointReader.read(checkpointDir);

      // If there is no checkpoint file, it could be no micro-batch job was executed at all.
      // To keep it safe, assuming there is unprocessed data.
      if (checkpointOption.isEmpty()) {
        return false;
      }

      Checkpoint checkpoint = checkpointOption.get();
      LOG.debug("Last checkpoint time is {}", checkpoint.checkpointTime());

      // This is a package private constructor in scala, but visible in Java
      // We have to use it to explicitly passing in the Checkpoint information without starting the context
      StreamingContext ssc = new StreamingContext(jssc.ssc().sparkContext(), checkpoint,
                                                  Durations.milliseconds(pipelineSpec.getBatchIntervalMillis()));

      ReceiverTracker receiverTracker = new ReceiverTracker(ssc, true);
      try {
        return !receiverTracker.hasUnallocatedBlocks();
      } finally {
        receiverTracker.stop(false);
      }

    } catch (Throwable t) {
      LOG.warn("Failed to read receiver metadata. Assuming there is unprocessed data.", t);
    }

    return false;
  }
}
