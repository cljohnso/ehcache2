/**
 *  Copyright Terracotta, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.sf.ehcache.util.counter;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;

import net.sf.ehcache.util.FailSafeTimer;
import net.sf.ehcache.util.counter.sampled.SampledCounter;
import net.sf.ehcache.util.counter.sampled.SampledCounterImpl;

/**
 * An implementation of a {@link CounterManager}.
 *
 * @author <a href="mailto:asanoujam@terracottatech.com">Abhishek Sanoujam</a>
 * @since 1.7
 *
 */
public class CounterManagerImpl implements CounterManager {

    private final FailSafeTimer timer;
    private boolean shutdown;
    private final List<Counter> counters = new ArrayList<Counter>();

    /**
     * Constructor that accepts a timer that will be used for scheduling sampled
     * counter if any is created
     */
    public CounterManagerImpl(FailSafeTimer timer) {
        if (timer == null) {
            throw new IllegalArgumentException("Timer cannot be null");
        }
        this.timer = timer;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        try {
            // do not cancel the timer as others might also be using it
            // instead shutdown the counters of this counterManager
            for (Counter counter : counters) {
                if (counter instanceof SampledCounter) {
                    ((SampledCounter) counter).shutdown();
                }
            }
        } finally {
            shutdown = true;
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized Counter createCounter(CounterConfig config) {
        if (shutdown) {
            throw new IllegalStateException("counter manager is shutdown");
        }
        if (config == null) {
            throw new NullPointerException("config cannot be null");
        }
        Counter counter = config.createCounter();
        addCounter(counter);
        return counter;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void addCounter(Counter counter) {
        if (counter instanceof SampledCounterImpl) {
            final SampledCounterImpl sampledCounter = (SampledCounterImpl) counter;
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    AccessController.doPrivileged(new PrivilegedAction<Object>() {
                        public Object run() {
                            sampledCounter.getTimerTask().run();
                            return null;
                        }
                    });
                }
            };
            timer.schedule(timerTask, sampledCounter.getIntervalMillis(), sampledCounter.getIntervalMillis());
        }
        counters.add(counter);
    }
    /**
     * {@inheritDoc}
     */
    public void shutdownCounter(Counter counter) {
        if (counter instanceof SampledCounter) {
            SampledCounter sc = (SampledCounter) counter;
            sc.shutdown();
        }
    }

}
