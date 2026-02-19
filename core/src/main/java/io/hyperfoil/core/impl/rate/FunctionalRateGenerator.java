package io.hyperfoil.core.impl.rate;

public abstract class FunctionalRateGenerator extends BaseRateGenerator {

   protected abstract long computeFireTimes(long elapsedTimeNs);

   protected abstract double computeFireTimeNs(long targetFireTimes);

   @Override
   public long computeNextFireTime(final long elapsedTimeNs, FireTimeListener listener) {
      if (elapsedTimeNs < fireTimeNs) {
         return (long) Math.ceil(fireTimeNs);
      }
      final long pastFireTimes = computeFireTimes(elapsedTimeNs);
      final long nextFireTimes = pastFireTimes + 1;
      final double nextFireTimeNs = computeFireTimeNs(nextFireTimes);
      fireTimeNs = nextFireTimeNs;
      long missingFireTimes = pastFireTimes - this.fireTimes;
      for (int i = 0; i < missingFireTimes; i++) {
         listener.onFireTime((long) Math.ceil(computeFireTimeNs(this.fireTimes + i + 1)));
      }
      this.fireTimes = pastFireTimes;
      return (long) Math.ceil(nextFireTimeNs);
   }
}
