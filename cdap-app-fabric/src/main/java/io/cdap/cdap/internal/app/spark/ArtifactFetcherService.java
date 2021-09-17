/*
 * Copyright © 2021 Cask Data, Inc.
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

package io.cdap.cdap.internal.app.spark;

import com.google.common.util.concurrent.AbstractIdleService;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.cdap.common.http.CommonNettyHttpServiceBuilder;
import io.cdap.http.NettyHttpService;
import org.apache.twill.filesystem.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Launches an HTTP server for fetching artifacts.
 */
public class ArtifactFetcherService extends AbstractIdleService {
  private static final Logger LOG = LoggerFactory.getLogger(ArtifactFetcherService.class);

  private final NettyHttpService httpService;

  public ArtifactFetcherService(CConfiguration cConf, Location bundleLocation) {
    httpService = new CommonNettyHttpServiceBuilder(cConf, "artifact.fetcher")
      .setHttpHandlers(
        new ArtifactFetcherHttpHandler(bundleLocation)
      )
      .setHost(cConf.get(Constants.Spark.Driver.ADDRESS))
      .setPort(cConf.getInt(Constants.Spark.Driver.PORT))
      .setExecThreadPoolSize(cConf.getInt(Constants.Spark.Driver.EXEC_THREADS))
      .setBossThreadPoolSize(cConf.getInt(Constants.Spark.Driver.BOSS_THREADS))
      .setWorkerThreadPoolSize(cConf.getInt(Constants.Spark.Driver.WORKER_THREADS))
      .build();
  }

  @Override
  protected void startUp() throws Exception {
    LOG.info("Starting ArtifactFetcherService");
    httpService.start();
    LOG.info("ArtifactFetcherService started");
  }

  @Override
  protected void shutDown() throws Exception {
    LOG.info("Stopping ArtifactFetcherService");
    httpService.stop(1, 2, TimeUnit.SECONDS);
    LOG.info("ArtifactFetcherService stopped");
  }

}
