package com.onceas.work;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Random;
import java.util.TimerTask;

import com.onceas.work.constraint.RequestClass;
import com.onceas.work.constraint.RequestManager;
import com.onceas.work.constraint.ServerWorkManagerImpl;
import com.onceas.work.constraint.ServiceClassStatsSupport;
import com.onceas.work.constraint.ServiceClassesStats;
import com.onceas.work.constraint.ThreadPriorityManager;

public final class IncrementAdvisor extends TimerTask {
	private static final class SmoothedStats {

		void add(double d) {
			double d1 = 1.0D - 1.0D / IncrementAdvisor.HORIZON;
			n = d1 * n + 1.0D;
			sum = d1 * sum + d;
			squaresSum = d1 * squaresSum + d * d;
		}

		double getAvg() {
			return sum / n;
		}

		boolean exceedsZ(double d, double d1) {
			double d2 = sum - n * d;
			return n > 1.0D && d2 * d2 > (n * squaresSum - sum * sum) * d1 * d1;
		}

		double zScore(double d) {
			double d1 = sum / n;
			return (d - d1) / Math.sqrt(squaresSum / n - d1 * d1);
		}

		double pLessThan(SmoothedStats smoothedstats) {
			double d = smoothedstats.sum / smoothedstats.n - sum / n;
			double d1 = (((squaresSum - (sum * sum) / n) + smoothedstats.squaresSum) - (smoothedstats.sum * smoothedstats.sum)
					/ smoothedstats.n)
					/ (n + smoothedstats.n);
			double d2 = d1 * (1.0D / n + 1.0D / smoothedstats.n);
			return d2 != 0.0D ? IncrementAdvisor.normCumulative(d
					/ Math.sqrt(d2)) : d <= 0.0D ? 0 : 1;
		}

		private double sum;

		private double squaresSum;

		private double n;

		SmoothedStats(double d) {
			n = 1.0D;
			sum = d;
			squaresSum = d * d;
		}
	}

	private static final int MIN_POOL_SIZE = initProperty(
			WorkManagerConstant.minPoolSizeProp, -1);

	// private static final int MIN_POOL_SIZE = 200;
	private static final int MAX_POOL_SIZE = initProperty(
			WorkManagerConstant.maxPoolSizeProp, -1);

	// private static final int MAX_POOL_SIZE = 1000;
	public static final double PERIOD = 2000D;

	private static final double NOVELTY_ATTRACTION = 0.5D;

	private static final Random RANDOM = new Random(123L);

	private static double HORIZON = 50D;

	private static final int Y_THRESHOLD_FOR_CPU_INTENSIVE_LOAD = 15000;

	private static final int HIGH_THROUGHPUT_THRESHOLD = 20000;

	private static final double NORM_CUMULATIVE[] = { 0.0013498980320000001D,
			0.0018658133009999999D, 0.0025551303310000001D,
			0.0034669738040000002D, 0.0046611880249999996D,
			0.0062096653260000001D, 0.0081975359259999995D,
			0.010724110021000001D, 0.013903447512999999D,
			0.017864420562000001D, 0.022750131948000001D,
			0.028716559815000001D, 0.035930319112000002D,
			0.044565462761999998D, 0.054799291698999997D,
			0.066807201270000005D, 0.080756659236000006D,
			0.096800484586000005D, 0.11506967022300001D, 0.13566606094799999D,
			0.158655253932D, 0.184060125347D, 0.21185539858300001D,
			0.24196365222399999D, 0.27425311775099998D, 0.30853753872599998D,
			0.34457825839D, 0.38208857781099997D, 0.42074029056200002D,
			0.46017216272299999D, 0.5D, 0.53982783727700001D,
			0.57925970943899996D, 0.61791142218899997D, 0.65542174161D,
			0.69146246127400002D, 0.72574688224899997D, 0.75803634777600004D,
			0.78814460141700005D, 0.815939874653D, 0.84134474606800003D,
			0.86433393905199996D, 0.88493032977700004D, 0.90319951541400001D,
			0.91924334076400005D, 0.93319279873000005D, 0.945200708301D,
			0.95543453723799998D, 0.96406968088800005D, 0.97128344018500001D,
			0.97724986805199998D, 0.98213557943800001D, 0.98609655248700001D,
			0.98927588997899996D, 0.99180246407399997D, 0.99379033467400002D,
			0.99533881197499996D, 0.99653302619600004D, 0.99744486966900003D,
			0.998134186699D, 0.99865010196799997D };

	private SmoothedStats throughput[];

	private int zeroCompletedDuration;

	private int previousCompleted;

	private long previousThreadTime;

	private long timeStamp;

	private final ServiceClassesStats stats = new ServiceClassesStats();

	private int attemptToIncrementCount;

	// the max completed jobs per second
	private int maxY;

	private double work_completed_per_second_duration;

	private double previousY;

	private double maxThroughput;

	private double lastThroughput;

	private int previousSampleIndex;

	private int nextSampleIndex;

	public IncrementAdvisor() {
		throughput = new SmoothedStats[0];
		timeStamp = System.currentTimeMillis();
		maxY = 0;
	}

	private static int initProperty(String s, int i) {
		try {
			return Integer.getInteger(s, i).intValue();
		} catch (SecurityException securityexception) {
			return i;
		} catch (NumberFormatException numberformatexception) {
			return i;
		}
	}

	private static double normCumulative(double d) {
		if (d < -3D)
			d = -3D;
		if (d > 3D)
			d = 3D;
		return NORM_CUMULATIVE[(int) (10D * (d + 3D))];
	}

	/**
	 * 
	 * @param i
	 *            current healthy threads count
	 * @param d
	 *            每秒完成的任务数
	 */
	private void addSample(int i, double d) {
		if (i == 0)
			return;
		if (debugEnabled())
			log("adding sample. n=" + i + ", " + d);
		SmoothedStats smoothedstats = throughput[i];
		if (smoothedstats == null) {
			smoothedstats = throughput[i] = new SmoothedStats(d);
		} else {
			if (smoothedstats.exceedsZ(d, 3D)) {
				HORIZON = (HORIZON + 1.0D) / 2D;
				if (debugEnabled())
					log("outlier z= " + smoothedstats.zScore(d) + ", y=" + d
							+ ", avg=" + smoothedstats.getAvg()
							+ " halve horizon to " + HORIZON);
			} else {
				HORIZON++;
			}
			smoothedstats.add(d);
		}
		lastThroughput = smoothedstats.getAvg();
	}

	private void showThroughput() {
		if (debugEnabled()) {
			String s = "";
			String s1 = "";
			for (int i = 0; i < throughput.length; i++) {
				SmoothedStats smoothedstats = throughput[i];
				s1 = s1
						+ (smoothedstats != null ? s
								+ (int) (smoothedstats.getAvg() + NOVELTY_ATTRACTION)
								: s + "N/A");
				s = "\t";
			}

			log(s1);
		}
	}

	public void run() {
		if (DebugWM.debug_FairShareRequest) {
			System.out.println("IncrementAdvisor:   run()");
		}
		boolean flag = debugEnabled();
		RequestManager requestmanager = RequestManager.getInstance();
		activeRequestClassesInOverload();
		int work_completed_total;
		long thread_usetime_total;
		do {
			work_completed_total = 0;
			thread_usetime_total = 0L;
			try {
				Iterator iterator = requestmanager.requestClasses.iterator();
				do {
					if (!iterator.hasNext())
						break;
					ServiceClassStatsSupport serviceclassstatssupport = (ServiceClassStatsSupport) iterator
							.next();
					if (serviceclassstatssupport != null) {
						work_completed_total += serviceclassstatssupport.getCompleted();
						thread_usetime_total += serviceclassstatssupport.getThreadUse();
					}
				} while (true);
				break;
			} catch (ConcurrentModificationException concurrentmodificationexception) {
			}
		} while (true);
		// j : work completed in l2
		int work_completed_duration = work_completed_total - previousCompleted;
		if (work_completed_duration == 0)
			zeroCompletedDuration++;
		else
			zeroCompletedDuration = 0;
		long now = System.currentTimeMillis();
		// l2 : time interval
		long time_interval = now - timeStamp;
		if (time_interval == 0L)
			return;
		// k : thread use time in this l2
		int thread_usetime_duration = (int) (thread_usetime_total - previousThreadTime);
		// 降低hog的标准，加快调整速度
		// int i1 = j != 0 && k >= 900 * j ? div(k, j) : 900;
		int judgment_hogthreads_index = work_completed_duration != 0 && 7 * thread_usetime_duration >= 4000 * work_completed_duration ? div(7 * thread_usetime_duration, work_completed_duration) : 4000;
		// j1 : current healthy threads count
		// System.out.println("Hog time = " + i1);
		int healthy_threads_length = requestmanager.purgeHogs(judgment_hogthreads_index);
		// System.out.println("Hog size = " + requestmanager.getHogSize());
		if (flag)
			log("active threads: "
					+ requestmanager.getActiveExecuteThreadCount()
					+ ", standby: " + requestmanager.getStandbyCount()
					+ ", idle: " + requestmanager.getIdleThreadCount()
					+ ", hogs: " + requestmanager.getHogSize());
		// k1 thread use time per second
		//only the time used by thread during sampling period above half of the interval time(1s) will cause the param  thread_usetime_per_second_duration greater than zero
		int thread_usetime_per_second_duration = (int) (((long) thread_usetime_duration + time_interval / 2L) / time_interval);
		// y : 每秒完成的任务数
		work_completed_per_second_duration = (1000D * (double) work_completed_duration) / (double) time_interval;

		if (flag)
			log("y,dCompleted,elapsedTime,threadUse,n,usedThreads,queuelength=\t"
					+ (int) (work_completed_per_second_duration + 0.5D)
					+ "\t"
					+ work_completed_duration
					+ "\t"
					+ time_interval
					+ "\t"
					+ thread_usetime_duration
					+ "\t"
					+ healthy_threads_length + "\t" + thread_usetime_per_second_duration + "\t" + requestmanager.queue.size());

		// i2 : queue length
		int queue_length = requestmanager.queue.size();

		if (debugEnabled()) {
			System.out.println("****************");
			System.out.println("");
			System.out.println("k1 = " + thread_usetime_per_second_duration + "  j1 = " + healthy_threads_length);
			System.out.println("k = " + thread_usetime_duration + "  l2 = " + time_interval);
		}
		if (queue_length > 0 && work_completed_duration > 0 && thread_usetime_per_second_duration < healthy_threads_length) {
			// thread utilization in last interval is lower than the predicted
			// utilization
			if (debugEnabled()) {
				System.out.println("Suspicious: " + work_completed_per_second_duration + "\t" + thread_usetime_per_second_duration);
				System.out.println("");
				System.out.println("****************");
			}
			return;
		}
		if (healthy_threads_length >= throughput.length) {
			SmoothedStats asmoothedstats[] = throughput;
			throughput = new SmoothedStats[healthy_threads_length + 1];
			System.arraycopy(asmoothedstats, 0, throughput, 0,
					asmoothedstats.length);
		}

		// thread utilization in last interval gets above the predicted
		// utilization
		if (thread_usetime_per_second_duration >= healthy_threads_length)
			addSample(healthy_threads_length, work_completed_per_second_duration);

		previousCompleted = work_completed_total;
		previousY = work_completed_per_second_duration;
		previousThreadTime = thread_usetime_total;
		timeStamp = now;
		stats.reset();
		try {
			RequestClass requestclass;
			for (Iterator iterator1 = requestmanager.requestClasses.iterator(); iterator1
					.hasNext(); requestclass.timeElapsed(time_interval, stats))
				requestclass = (RequestClass) iterator1.next();

		} catch (ConcurrentModificationException concurrentmodificationexception1) {
		}
		int min_threadpool_size = getMinThreadPoolSize();
		//
		if (healthy_threads_length < min_threadpool_size) {
			// 如果健康线程个数小于最小线程池大小，则补充相应的差额
			if (debugEnabled()) {
				System.out.println("如果健康线程个数小于最小线程池大小，则补充相应的差额");
				System.out.println("");
				System.out.println("****************");
			}
			//  如果健康线程个数小于最小线程池大小，则逐一递增
//			requestmanager.incrPoolSize(j2 - j1);
			requestmanager.incrPoolSize(1);
			return;
		}

		if (queue_length > 0 && zeroCompletedDuration > 2) {
			// 等待队列不为空，且连续两个Interval没有任务完成，即大于4s
			requestmanager.incrPoolSize(1);
			if (debugEnabled()) {
				System.out.println("等待队列不为空，且连续两个Interval没有任务完成，增加线程池容量 +1");
				System.out.println("");
				System.out.println("****************");
			}
			return;
		}
		// Y_THRESHOLD_FOR_CPU_INTENSIVE_LOAD = 15000
		if (maxY == 0 && work_completed_per_second_duration > Y_THRESHOLD_FOR_CPU_INTENSIVE_LOAD) {
			// 第一次有任务到来，每秒完成的任务数超过Y_THRESHOLD_FOR_CPU_INTENSIVE_LOAD，则减少线程数至CPU数量
			reset(requestmanager, healthy_threads_length);
			if (debugEnabled()) {
				System.out
						.println("第一次有任务到来，每秒完成的任务数超过Y_THRESHOLD_FOR_CPU_INTENSIVE_LOAD，则减少线程数至CPU数量");
				System.out.println("");
				System.out.println("****************");
			}
			return;
		}
		ThreadPriorityManager.getInstance().computeThreadPriorities(
				requestmanager.requestClasses);
		initMaxValues(requestmanager.getTotalRequestsCount(), work_completed_per_second_duration);
		// showThroughput();
		initIndexes(healthy_threads_length);
		double d = RANDOM.nextFloat();
		double d1 = getDecrAttraction(healthy_threads_length, min_threadpool_size);
		double d2 = getIncrAttraction(healthy_threads_length);
		if (debugEnabled()) {
			System.out.println("attraction decr=" + d1 + ", incr=" + d2
					+ ", rand=" + d);
		}
		if (d1 > d2) {
			if (d1 > d) {
				if (/*debugEnabled()*/true) {
					System.out.println("incrPoolSize = "
							+ (previousSampleIndex - healthy_threads_length));
					System.out.println("");
					System.out.println("****************");
				}
				requestmanager.incrPoolSize(previousSampleIndex - healthy_threads_length);
			}
			attemptToIncrementCount = 0;
			return;
		}
		// attemptToIncrementCount 3
		// if(i2 > 0 && j1 < getMaxThreadPoolSize() && (attemptToIncrementCount
		// >= 0 || d2 > d))
		if (queue_length > 0 && healthy_threads_length < getMaxThreadPoolSize()
				&& (attemptToIncrementCount >= 3 || d2 > d)) {
			attemptToIncrementCount = 0;
			int k2 = 1;
			if (!mustIncrementByOne(d2, d1, healthy_threads_length, nextSampleIndex))
				k2 = Math.max(nextSampleIndex - healthy_threads_length,
						getIncrementInterval((int) work_completed_per_second_duration));

			requestmanager.incrPoolSize(k2);
			if (debugEnabled()) {
				System.out.println("nextSampleIndex - j1 = "
						+ (nextSampleIndex - healthy_threads_length));
				System.out.println("getIncrementInterval = "
						+ (getIncrementInterval((int) work_completed_per_second_duration)));
				System.out
						.println("attemptToIncrementCount >= 3 || d2 > d Growing with attraction= "
								+ d2 + ", increment interval=" + k2);
				System.out.println("");
				System.out.println("****************");
			}
			return;
		} else {
			attemptToIncrementCount++;
			return;
		}
	}

	private boolean mustIncrementByOne(double d, double d1, int i, int j) {
		if (j > i + 1 && d1 == 0.0013498980320000001D
				&& d == 0.0013498980320000001D) {
			SmoothedStats asmoothedstats[] = throughput;
			throughput = new SmoothedStats[i];
			System.arraycopy(asmoothedstats, 0, throughput, 0,
					throughput.length);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * If the availableProcessors is not richful, then we will decrPoolSize
	 * 
	 * @param requestmanager
	 * @param i
	 */
	private static void reset(RequestManager requestmanager, int i) {
		int j = Runtime.getRuntime().availableProcessors();
		if (i <= j)
			return;
		if (debugEnabled())
			log("resetting thread count to cpucount=" + j);
		// decrPoolSize
		requestmanager.incrPoolSize(j - i);
	}

	/**
	 * 
	 * @param i
	 *            current total request count
	 * @param d
	 *            completed jobs per second
	 */
	//maxThroughout in consideration of history data while maxY just reflect current value
	private void initMaxValues(int i, double d) {
		if (i == 0) {
			if (debugEnabled())
				log("RESETTING maxThroughput and maxY");
			maxThroughput = maxY = 0;
			lastThroughput = 0.0D;
		} else {
			maxThroughput = Math.max(maxThroughput, lastThroughput);
			if (debugEnabled())
				log("maxThroughput=" + maxThroughput + ",lastThroughput="
						+ lastThroughput);
			maxY = (int) Math.max(maxY, d);
			if (debugEnabled())
				log("maxY=" + maxY + ", y=" + d);
		}
	}

	private void initIndexes(int i) {
		previousSampleIndex = 0;
		nextSampleIndex = 0;
		int j = i - 1;
		do {
			if (j < 0)
				break;
			if (throughput[j] != null) {
				previousSampleIndex = j;
				break;
			}
			j--;
		} while (true);
		j = i + 1;
		do {
			if (j >= throughput.length)
				break;
			if (throughput[j] != null) {
				nextSampleIndex = j;
				break;
			}
			j++;
		} while (true);
	}

	private int getIncrementInterval(int i) {
		if (maxY == 0 || i == 0)
			return 1;
		int j = maxY / i;
		if (j <= 1)
			return 1;
		// 最大不能超过20
		int k = Math.min(20, 3 * j + 1);
		if (debugEnabled())
			log("Calculated increment interval=" + k);
		return k;
	}

	private void activeRequestClassesInOverload() {
		RequestManager requestmanager = RequestManager.getInstance();
		int i = ServerWorkManagerImpl.SHARED_OVERLOAD_MANAGER.getCapacity();
		if (requestmanager.getTotalRequestsCount() >= i)
			requestmanager.activeRequestClassesInOverload(i);
		else
			requestmanager.resetActiveRequestClasses();
	}

	private static int div(int i, int j) {
		return (i + j / 2) / j;
	}

	private double getIncrAttraction(int i) {
		if (debugEnabled())
			log("[getIncrAttraction] next=" + nextSampleIndex + ", current="
					+ i);
		if (nextSampleIndex == 0)
			return NOVELTY_ATTRACTION;//why not 1?
		SmoothedStats smoothedstats = throughput[nextSampleIndex];
		if (smoothedstats == null || throughput[i] == null)
			return NOVELTY_ATTRACTION;
		else
			return throughput[i].pLessThan(smoothedstats);
	}

	/**
	 * @param i
	 *            health threads count
	 * @param j
	 *            min thread pool size
	 * @return
	 */
	private double getDecrAttraction(int i, int j) {
		if (debugEnabled())
			log("[getDecrAttraction] previous=" + previousSampleIndex
					+ ", current=" + i);
		if (i <= j || previousSampleIndex == 0)
			return 0.0D;
		SmoothedStats smoothedstats = throughput[previousSampleIndex];
		if (smoothedstats == null || throughput[i] == null)
			// 0.5
			return NOVELTY_ATTRACTION;
		double d = throughput[i].pLessThan(smoothedstats);
		// HIGH_THROUGHPUT_THRESHOLD = 20000
		if (d > NOVELTY_ATTRACTION
				&& lastThroughput < HIGH_THROUGHPUT_THRESHOLD
				&& notEnoughVariationFromMax()) {
			if (debugEnabled())
				log("decrAttraction is " + d + " but limiting it to 0.5");
			return NOVELTY_ATTRACTION;
		} else {
			return d;
		}
	}

	// 与 max 相比的变化是否足够大
	private boolean notEnoughVariationFromMax() {
		if (lastThroughput >= maxThroughput)
			return true;
		double d = maxThroughput - lastThroughput;
		return d * 100D <= 20D * maxThroughput;
	}

	public static int getMinThreadPoolSize() {
		if (MIN_POOL_SIZE > 0)
			return MIN_POOL_SIZE;
		return 200;
	}

	public static int getMaxThreadPoolSize() {
		if (MAX_POOL_SIZE > 0)
			return MAX_POOL_SIZE;
		return 1000;
	}

	public double getThroughput() {
		return work_completed_per_second_duration;
	}

	private static boolean debugEnabled() {
		return false;
	}

	private static void log(String s) {
		if (DebugWM.debug_IncreAD)
			System.out.println("<IncrAdvisor>" + s);
	}

}