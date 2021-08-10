/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.stress.cli.fuse;

import alluxio.Constants;
import alluxio.stress.BaseParameters;
import alluxio.stress.StressConstants;
import alluxio.stress.cli.Benchmark;
import alluxio.stress.common.SummaryStatistics;
import alluxio.stress.fuse.FuseIOOperation;
import alluxio.stress.fuse.FuseIOParameters;
import alluxio.stress.fuse.FuseIOTaskResult;
import alluxio.util.CommonUtils;
import alluxio.util.FormatUtils;
import alluxio.util.executor.ExecutorServiceFactories;

import com.beust.jcommander.ParametersDelegate;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single node stress test.
 */
public class FuseIOBench extends Benchmark<FuseIOTaskResult> {
  private static final Logger LOG = LoggerFactory.getLogger(FuseIOBench.class);

  @ParametersDelegate
  private FuseIOParameters mParameters = new FuseIOParameters();

  /** Set to true after the first barrier is passed. */
  private volatile boolean mStartBarrierPassed = false;

  /**
   * Creates instance.
   */
  public FuseIOBench() {
  }

  /**
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    mainInternal(args, new FuseIOBench());
  }

  @Override
  public void prepare() throws Exception {
    for (Integer numOfThreads: mParameters.mThreads) {
      if (numOfThreads > mParameters.mNumFiles) {
        throw new IllegalArgumentException(String
            .format("Number of threads (%d) must be larger than number of files (%d)",
                numOfThreads, mParameters.mNumFiles));
      }
    }
    if (mParameters.mReadRandom) {
      LOG.warn("Random read is not supported for now. Read sequentially");
      // TODO(Shawn): support random read
      mParameters.mReadRandom = false;
    }
    if (mParameters.mOperation == FuseIOOperation.WRITE) {
      LOG.warn("Cannot write repeatedly, so warmup is not possible. Setting warmup to 0s.");
      mParameters.mWarmup = "0s";
    }
  }

  @Override
  public FuseIOTaskResult runLocal() throws Exception {
    List<Integer> threadCounts = new ArrayList<>(mParameters.mThreads);
    threadCounts.sort(Comparator.comparingInt(i -> i));

    FuseIOTaskResult taskResult = new FuseIOTaskResult();
    taskResult.setBaseParameters(mBaseParameters);
    taskResult.setParameters(mParameters);

    for (Integer numThreads: threadCounts) {
      FuseIOTaskResult.ThreadCountResult threadCountResult = runForThreadCount(numThreads);
      taskResult.addThreadCountResults(numThreads, threadCountResult);
    }
    return taskResult;
  }

  private FuseIOTaskResult.ThreadCountResult runForThreadCount(int numThreads) throws Exception {
    LOG.info("Running benchmark for thread count: " + numThreads);
    ExecutorService service =
        ExecutorServiceFactories.fixedThreadPool("bench-thread", numThreads).create();
    long durationMs = FormatUtils.parseTimeSize(mParameters.mDuration);
    long warmupMs = FormatUtils.parseTimeSize(mParameters.mWarmup);
    long startMs = mBaseParameters.mStartMs;
    if (startMs == BaseParameters.UNDEFINED_START_MS || mStartBarrierPassed) {
      // if the barrier was already passed, then overwrite the start time
      startMs = CommonUtils.getCurrentMs() + 10000;
    }
    long endMs = startMs + warmupMs + durationMs;
    BenchContext context = new BenchContext(startMs, endMs);

    List<Callable<Void>> callables = new ArrayList<>(numThreads);
    for (int i = 0; i < numThreads; i++) {
      callables.add(new BenchThread(context, i, numThreads));
    }
    service.invokeAll(callables, FormatUtils.parseTimeSize(mBaseParameters.mBenchTimeout),
        TimeUnit.MILLISECONDS);

    service.shutdownNow();
    service.awaitTermination(30, TimeUnit.SECONDS);

    FuseIOTaskResult.ThreadCountResult result = context.getResult();

    LOG.info(String.format("thread count: %d, errors: %d, IO throughput (MB/s): %f", numThreads,
        result.getErrors().size(), result.getIOMBps()));

    return result;
  }

  /**
   * Reads the log file from java agent log file.
   *
   * @param startMs start time for profiling
   * @param endMs end time for profiling
   * @return TimeToFirstByteStatistics
   * @throws IOException
   */
  @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME")
  public synchronized Map<String, SummaryStatistics> addAdditionalResult(
      long startMs, long endMs) throws IOException {
    Map<String, SummaryStatistics> summaryStatistics = new HashMap<>();

    Map<String, MethodStatistics> nameStatistics =
        processMethodProfiles(startMs, endMs, profileInput -> {
          if (profileInput.getIsttfb()) {
            return profileInput.getMethod();
          }
          return null;
        });
    if (!nameStatistics.isEmpty()) {
      for (Map.Entry<String, MethodStatistics> entry : nameStatistics.entrySet()) {
        summaryStatistics.put(
            entry.getKey(), toSummaryStatistics(entry.getValue()));
      }
    }

    return summaryStatistics;
  }

  /**
   * Converts this class to {@link SummaryStatistics}.
   *
   * @param methodStatistics the method statistics
   * @return new SummaryStatistics
   */
  private SummaryStatistics toSummaryStatistics(MethodStatistics methodStatistics) {
    float[] responseTimePercentile = new float[101];
    for (int i = 0; i <= 100; i++) {
      responseTimePercentile[i] =
          (float) methodStatistics.getTimeNs().getValueAtPercentile(i) / Constants.MS_NANO;
    }

    float[] responseTime99Percentile = new float[StressConstants.TIME_99_COUNT];
    for (int i = 0; i < responseTime99Percentile.length; i++) {
      responseTime99Percentile[i] = (float) methodStatistics.getTimeNs()
          .getValueAtPercentile(100.0 - 1.0 / (Math.pow(10.0, i))) / Constants.MS_NANO;
    }

    float[] maxResponseTimesMs = new float[StressConstants.MAX_TIME_COUNT];
    Arrays.fill(maxResponseTimesMs, -1);
    for (int i = 0; i < methodStatistics.getMaxTimeNs().length; i++) {
      maxResponseTimesMs[i] = (float) methodStatistics.getMaxTimeNs()[i] / Constants.MS_NANO;
    }

    return new SummaryStatistics(methodStatistics.getNumSuccess(),
        responseTimePercentile,
        responseTime99Percentile, maxResponseTimesMs);
  }

  private final class BenchContext {
    private final long mStartMs;
    private final long mEndMs;

    /** The results. Access must be synchronized for thread safety. */
    private FuseIOTaskResult.ThreadCountResult mThreadCountResult;

    public BenchContext(long startMs, long endMs) {
      mStartMs = startMs;
      mEndMs = endMs;
    }

    public long getStartMs() {
      return mStartMs;
    }

    public long getEndMs() {
      return mEndMs;
    }

    public synchronized void mergeThreadResult(FuseIOTaskResult.ThreadCountResult threadResult) {
      if (mThreadCountResult == null) {
        mThreadCountResult = threadResult;
      } else {
        try {
          mThreadCountResult.merge(threadResult);
        } catch (Exception e) {
          mThreadCountResult.addErrorMessage(e.getMessage());
        }
      }
    }

    public synchronized FuseIOTaskResult.ThreadCountResult getResult() {
      return mThreadCountResult;
    }
  }

  private final class BenchThread implements Callable<Void> {
    private final BenchContext mContext;
    private final List<String> mFilesPath;
    private final int mThreadId;
    private final byte[] mBuffer;
    private final long mFileSize;

    private FileInputStream mInStream = null;
    private FileOutputStream mOutStream = null;
    private long mCurrentOffset;

    private final FuseIOTaskResult.ThreadCountResult mThreadCountResult =
        new FuseIOTaskResult.ThreadCountResult();

    private BenchThread(BenchContext context, int threadId, int numThreads) {
      mContext = context;
      mThreadId = threadId;
      mFilesPath = new ArrayList<>();
      for (int i = mThreadId; i < mParameters.mNumFiles; i += numThreads) {
        mFilesPath.add(mParameters.mLocalPath + "/data-" + i);
      }

      mBuffer = new byte[(int) FormatUtils.parseSpaceSize(mParameters.mBufferSize)];
      Arrays.fill(mBuffer, (byte) 'A');

      mFileSize = FormatUtils.parseSpaceSize(mParameters.mFileSize);
    }

    @Override
    public Void call() {
      try {
        runInternal();
      } catch (Exception e) {
        LOG.error(Thread.currentThread().getName() + ": failed", e);
        mThreadCountResult.addErrorMessage(e.getMessage());
      } finally {
        closeInStream();
        closeOutStream();
      }

      // Update thread count result
      mThreadCountResult.setEndMs(CommonUtils.getCurrentMs());
      mContext.mergeThreadResult(mThreadCountResult);

      return null;
    }

    private void runInternal() throws Exception {
      // When to start recording measurements
      long recordMs = mContext.getStartMs() + FormatUtils.parseTimeSize(mParameters.mWarmup);
      mThreadCountResult.setRecordStartMs(recordMs);
      boolean isRead = FuseIOOperation.isRead(mParameters.mOperation);

      long waitMs = mContext.getStartMs() - CommonUtils.getCurrentMs();
      if (waitMs < 0) {
        throw new IllegalStateException(String.format(
            "Thread missed barrier. Set the start time to a later time. start: %d current: %d",
            mContext.getStartMs(), CommonUtils.getCurrentMs()));
      }
      CommonUtils.sleepMs(waitMs);
      mStartBarrierPassed = true;

      for (int i = 0; i < mFilesPath.size(); i++) {
        mCurrentOffset = 0;
        String filePath = mFilesPath.get(i);
        while (!Thread.currentThread().isInterrupted()) {
          if (isRead && CommonUtils.getCurrentMs() > mContext.getEndMs()) {
            closeInStream();
            return;
          }
          long ioBytes = applyOperation(filePath);

          // Start recording after the warmup
          if (CommonUtils.getCurrentMs() > recordMs) {
            if (ioBytes > 0) {
              mThreadCountResult.incrementIOBytes(ioBytes);
            } else {
              // Done reading/writing one file
              break;
            }
          }
        }
      }
      // Done reading all files
      if (isRead) {
        throw new IllegalArgumentException(String.format("Thread %d finishes reading all its files"
            + "before the bench ends. For more accurate result, use more files, or larger files, "
            + "or a shorter duration", mThreadId));
      }
    }

    private long applyOperation(String filePath) throws IOException {
      if (FuseIOOperation.isRead(mParameters.mOperation) && mInStream == null) {
        mInStream = new FileInputStream(filePath);
      }
      switch (mParameters.mOperation) {
        case READ: {
          int bytesRead = mInStream.read(mBuffer);
          if (bytesRead < 0) {
            closeInStream();
          }
          return bytesRead;
        }
        case WRITE: {
          if (mOutStream == null) {
            mOutStream = new FileOutputStream(filePath);
          }
          int bytesToWrite = (int) Math.min(mFileSize - mCurrentOffset, mBuffer.length);
          if (bytesToWrite == 0) {
            closeOutStream();
            return -1;
          }
          mOutStream.write(mBuffer, 0, bytesToWrite);
          mCurrentOffset += bytesToWrite;
          return bytesToWrite;
        }
        default:
          throw new IllegalStateException("Unknown operation: " + mParameters.mOperation);
      }
    }

    private void closeInStream() {
      try {
        if (mInStream != null) {
          mInStream.close();
        }
      } catch (IOException e) {
        mThreadCountResult.addErrorMessage(e.getMessage());
      } finally {
        mInStream = null;
      }
    }

    private void closeOutStream() {
      try {
        if (mOutStream != null) {
          mOutStream.close();
        }
      } catch (IOException e) {
        mThreadCountResult.addErrorMessage(e.getMessage());
      } finally {
        mOutStream = null;
      }
    }
  }
}
