package com.continuuity.data2.transaction;

import com.continuuity.data2.transaction.inmemory.InMemoryTransactionManager;
import com.continuuity.data2.transaction.inmemory.InMemoryTxSystemClient;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * Tests the transaction executor.
 */
public class TransactionExecutorTest {

  final DummyTxClient txSystem = new DummyTxClient(new InMemoryTransactionManager());
  final DummyTxAware ds1 = new DummyTxAware(), ds2 = new DummyTxAware();

  private TransactionExecutor getExecutor() {
    return new TransactionExecutor(txSystem, ds1, ds2);
  }

  static final byte[] a = { 'a' };
  static final byte[] b = { 'b' };

  final Function<Integer, Integer> testFunction = new Function<Integer, Integer>() {
    @Nullable
    @Override
    public Integer apply(@Nullable Integer input) {
      ds1.addChange(a);
      ds2.addChange(b);
      return input * input;
    }
  };

  @Test
  public void testSuccessful() throws TransactionFailureException {
    // execute: add a change to ds1 and ds2
    Integer result = getExecutor().execute(testFunction, 10);
    // verify both are committed and post-committed
    Assert.assertTrue(ds1.committed);
    Assert.assertTrue(ds2.committed);
    Assert.assertTrue(ds1.postCommitted);
    Assert.assertTrue(ds2.postCommitted);
    Assert.assertTrue(100 == result);
  }

  @Test
  public void testPostCommitFailure() throws TransactionFailureException {
    ds1.failPostCommitTxOnce = 2;
    // execute: add a change to ds1 and ds2
    try {
      getExecutor().execute(testFunction, 10);
      Assert.fail("post commit failed - exception should be thrown");
    } catch (TransactionFailureException e) {
      Assert.assertEquals("post failure", e.getCause().getMessage());
    }
    // verify both are committed and post-committed
    Assert.assertTrue(ds1.committed);
    Assert.assertTrue(ds2.committed);
    Assert.assertTrue(ds1.postCommitted);
    Assert.assertTrue(ds2.postCommitted);
  }

  @Test
  public void testPersistFailure() throws TransactionFailureException {
    ds1.failCommitTxOnce = 2;
    // execute: add a change to ds1 and ds2
    try {
      getExecutor().execute(testFunction, 10);
      Assert.fail("persist failed - exception should be thrown");
    } catch (TransactionFailureException e) {
      Assert.assertEquals("persist failure", e.getCause().getMessage());
    }
    // verify both are committed and post-committed
    Assert.assertTrue(ds1.committed);
    Assert.assertFalse(ds2.committed);
    Assert.assertFalse(ds1.postCommitted);
    Assert.assertFalse(ds2.postCommitted);
    Assert.assertTrue(ds1.rolledBack);
    Assert.assertTrue(ds2.rolledBack);
    Assert.assertEquals(txSystem.state, DummyTxClient.CommitState.Aborted);
  }

  @Test
  public void testPersistFalse() throws TransactionFailureException {
    ds1.failCommitTxOnce = 1;
    // execute: add a change to ds1 and ds2
    try {
      getExecutor().execute(testFunction, 10);
      Assert.fail("persist failed - exception should be thrown");
    } catch (TransactionFailureException e) {
      Assert.assertEquals("persist failure", e.getCause().getMessage());
    }
    // verify both are committed and post-committed
    Assert.assertTrue(ds1.committed);
    Assert.assertFalse(ds2.committed);
    Assert.assertFalse(ds1.postCommitted);
    Assert.assertFalse(ds2.postCommitted);
    Assert.assertTrue(ds1.rolledBack);
    Assert.assertTrue(ds2.rolledBack);
    Assert.assertEquals(txSystem.state, DummyTxClient.CommitState.Aborted);
  }

  @Test
  public void testPersistAndRollbackFailure() throws TransactionFailureException {
    ds2.failCommitTxOnce = 2;
    ds1.failRollbackTxOnce = 2;
    // execute: add a change to ds1 and ds2
    try {
      getExecutor().execute(testFunction, 10);
      Assert.fail("persist failed - exception should be thrown");
    } catch (TransactionFailureException e) {
      Assert.assertEquals("persist failure", e.getCause().getMessage());
    }
    // verify both are committed and post-committed
    Assert.assertTrue(ds1.committed);
    Assert.assertFalse(ds2.committed);
    Assert.assertFalse(ds1.postCommitted);
    Assert.assertFalse(ds2.postCommitted);
    Assert.assertTrue(ds1.rolledBack);
    Assert.assertTrue(ds2.rolledBack); // ensure ds2 got rolled back even though ds1 failed to roll back
    Assert.assertEquals(txSystem.state, DummyTxClient.CommitState.Invalidated);
  }

  @Test
  public void testPersistAndRollbackFalse() throws TransactionFailureException {
    ds2.failCommitTxOnce = 1;
    ds1.failRollbackTxOnce = 1;
    // execute: add a change to ds1 and ds2
    try {
      getExecutor().execute(testFunction, 10);
      Assert.fail("persist failed - exception should be thrown");
    } catch (TransactionFailureException e) {
      Assert.assertEquals("persist failure", e.getCause().getMessage());
    }
    // verify both are committed and post-committed
    Assert.assertTrue(ds1.committed);
    Assert.assertFalse(ds2.committed);
    Assert.assertFalse(ds1.postCommitted);
    Assert.assertFalse(ds2.postCommitted);
    Assert.assertTrue(ds1.rolledBack);
    Assert.assertTrue(ds2.rolledBack); // ensure ds2 got rolled back even though ds1 failed to roll back
    Assert.assertEquals(txSystem.state, DummyTxClient.CommitState.Invalidated);
  }

  @Test
  public void testCommitFalse() throws TransactionFailureException {
    txSystem.failCommitOnce = true;
    // execute: add a change to ds1 and ds2
    try {
      getExecutor().execute(testFunction, 10);
      Assert.fail("commit failed - exception should be thrown");
    } catch (TransactionConflictException e) {
      Assert.assertNull(e.getCause());
    }
    // verify both are committed and post-committed
    Assert.assertTrue(ds1.committed);
    Assert.assertTrue(ds2.committed);
    Assert.assertFalse(ds1.postCommitted);
    Assert.assertFalse(ds2.postCommitted);
    Assert.assertTrue(ds1.rolledBack);
    Assert.assertTrue(ds2.rolledBack);
    Assert.assertEquals(txSystem.state, DummyTxClient.CommitState.Aborted);
  }


  static class DummyTxAware implements TransactionAware {

    Transaction tx;
    boolean committed = false, checked = false, rolledBack = false, postCommitted = false;
    List<byte[]> changes = Lists.newArrayList();

    int failStartTxOnce = 0; // 0 = true, 1 = false, 2 = throw
    int failChangesTxOnce = 0; // 0 = true, 1 = false, 2 = throw
    int failCommitTxOnce = 0; // 0 = true, 1 = false, 2 = throw
    int failPostCommitTxOnce = 0; // 0 = true, 1 = false, 2 = throw
    int failRollbackTxOnce = 0; // 0 = true, 1 = false, 2 = throw

    void addChange(byte[] key) {
      changes.add(key);
    }

    @Override
    public void startTx(Transaction tx) {
      this.tx = tx;
      checked = false;
      committed = false;
      rolledBack = false;
      postCommitted = false;
      changes.clear();
      if (failStartTxOnce == 2) {
        throw new RuntimeException("start failure");
      }
    }

    @Override
    public Collection<byte[]> getTxChanges() {
      checked = true;
      if (failChangesTxOnce == 2) {
        throw new RuntimeException("changes failure");
      }
      return ImmutableList.copyOf(changes);
    }

    @Override
    public boolean commitTx() throws Exception {
      committed = true;
      if (failCommitTxOnce == 2) {
        throw new RuntimeException("persist failure");
      } else {
        return failCommitTxOnce == 0;
      }
    }

    @Override
    public void postTxCommit() {
      postCommitted = true;
      if (failPostCommitTxOnce == 2) {
        throw new RuntimeException("post failure");
      };
    }

    @Override
    public boolean rollbackTx() throws Exception {
      rolledBack = true;
      if (failRollbackTxOnce == 2) {
        throw new RuntimeException("rollback failure");
      } else {
        return failRollbackTxOnce == 0;
      }
    }

    @Override
    public String getName() {
      return "dummy";
    }
  }

  static class DummyTxClient extends InMemoryTxSystemClient {

    boolean failCanCommitOnce = false;
    boolean failCommitOnce = false;
    enum CommitState {
      Started, Committed, Aborted, Invalidated
    }
    CommitState state = CommitState.Started;

    DummyTxClient(InMemoryTransactionManager txmgr) {
      super(txmgr);
    }

    @Override
    public boolean canCommit(Transaction tx, Collection<byte[]> changeIds) {
      if (failCanCommitOnce) {
        failCanCommitOnce = false;
        return false;
      } else {
        return super.canCommit(tx, changeIds);
      }
    }

    @Override
    public boolean commit(Transaction tx) {
      if (failCommitOnce) {
        failCommitOnce = false;
        return false;
      } else {
        state = CommitState.Committed;
        return super.commit(tx);
      }
    }

    @Override
    public Transaction startLong() {
      state = CommitState.Started;
      return super.startLong();
    }

    @Override
    public Transaction startShort() {
      state = CommitState.Started;
      return super.startShort();
    }

    @Override
    public Transaction startShort(int timeout) {
      state = CommitState.Started;
      return super.startShort(timeout);
    }

    @Override
    public void abort(Transaction tx) {
      state = CommitState.Aborted;
      super.abort(tx);
    }

    @Override
    public void invalidate(Transaction tx) {
      state = CommitState.Invalidated;
      super.invalidate(tx);
    }
  }
}
