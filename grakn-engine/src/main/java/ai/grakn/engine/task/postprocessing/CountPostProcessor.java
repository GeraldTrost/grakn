/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016-2018 Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.engine.task.postprocessing;

import ai.grakn.GraknConfigKey;
import ai.grakn.GraknTxType;
import ai.grakn.Keyspace;
import ai.grakn.concept.ConceptId;
import ai.grakn.engine.GraknConfig;
import ai.grakn.engine.factory.EngineGraknTxFactory;
import ai.grakn.engine.lock.LockProvider;
import ai.grakn.kb.internal.EmbeddedGraknTx;
import ai.grakn.kb.log.CommitLog;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * <p>
 *     Class responsible for updating the number of {@link ai.grakn.concept.Thing}s each {@link ai.grakn.concept.Type}
 *     currently has.
 * </p>
 *
 * @author Filipe Peliz Pinto Teixeira
 */
public class CountPostProcessor {
    private final static Logger LOG = LoggerFactory.getLogger(CountPostProcessor.class);
    private final RedisCountStorage redis;
    private final MetricRegistry metricRegistry;
    private final EngineGraknTxFactory factory;
    private final LockProvider lockProvider;
    private final long shardingThreshold;

    private CountPostProcessor(GraknConfig engineConfig, EngineGraknTxFactory factory, LockProvider lockProvider, MetricRegistry metricRegistry, RedisCountStorage countStorage) {
        this.redis = countStorage;
        this.shardingThreshold = engineConfig.getProperty(GraknConfigKey.SHARDING_THRESHOLD);
        this.metricRegistry = metricRegistry;
        this.factory = factory;
        this.lockProvider = lockProvider;
    }

    public static CountPostProcessor create(GraknConfig engineConfig, EngineGraknTxFactory factory, LockProvider lockProvider, MetricRegistry metricRegistry, RedisCountStorage countStorage) {
        return new CountPostProcessor(engineConfig, factory, lockProvider, metricRegistry, countStorage);
    }

    /**
     * Updates the counts of {@link ai.grakn.concept.Type}s based on the commit logs received.
     *
     * @param commitLog The commit log containing the details of the job
     */
    public void updateCounts(CommitLog commitLog){
        try (Timer.Context context = metricRegistry.timer(name(CountPostProcessor.class, "execution")).time()) {
            Map<ConceptId, Long> jobs = commitLog.instanceCount();
            metricRegistry.histogram(name(CountPostProcessor.class, "jobs"))
                    .update(jobs.size());

            //We Use redis to keep track of counts in order to ensure sharding happens in a centralised manner.
            //The graph cannot be used because each engine can have it's own snapshot of the graph with caching which makes
            //values only approximately correct
            Set<ConceptId> conceptToShard = new HashSet<>();

            //Update counts
            jobs.forEach((key, value) -> {
                metricRegistry
                        .histogram(name(CountPostProcessor.class, "shard-size-increase"))
                        .update(value);
                Timer.Context contextSingle = metricRegistry
                        .timer(name(CountPostProcessor.class, "execution-single")).time();
                try {
                    if (incrementInstanceCountAndCheckIfShardingIsNeeded(redis, commitLog.keyspace(), key, value, shardingThreshold)) {
                        conceptToShard.add(key);
                    }
                } finally {
                    contextSingle.stop();
                }
            });

            //Shard anything which requires sharding
            conceptToShard.forEach(type -> {
                Timer.Context contextSharding = metricRegistry.timer("sharding").time();
                try {
                    shardConcept(redis, factory, commitLog.keyspace(), type, shardingThreshold);
                } finally {
                    contextSharding.stop();
                }
            });
            LOG.debug("Updating instance count successful for {} tasks", jobs.size());
        } catch(Exception e) {
            LOG.error("Could not terminate task", e);
            throw e;
        }
    }

    /**
     * Updates the type counts in redis and checks if sharding is needed.
     *
     * @param keyspace The keyspace of the graph which the type comes from
     * @param conceptId The id of the concept with counts to update
     * @param incrementBy The number of instances which the type has gained/lost
     * @return true if sharding is needed.
     */
    private static boolean incrementInstanceCountAndCheckIfShardingIsNeeded(RedisCountStorage redis, Keyspace keyspace, ConceptId conceptId, long incrementBy, long shardingThreshold){
        long numShards = redis.getCount(RedisCountStorage.getKeyNumShards(keyspace, conceptId));
        if(numShards == 0) numShards = 1;
        long numInstances = redis.incrementCount(RedisCountStorage.getKeyNumInstances(keyspace, conceptId), incrementBy);
        return numInstances > shardingThreshold * numShards;
    }

    /**
     * Performs the high level sharding operation. This includes:
     * - Acquiring a lock to ensure only one thing can shard
     * - Checking if sharding is still needed after having the lock
     * - Actually sharding
     * - Incrementing the number of shards on each type
     *
     * @param keyspace The database containing the {@link ai.grakn.concept.Type} to shard
     * @param conceptId The id of the concept to shard
     */
    private void shardConcept(RedisCountStorage redis, EngineGraknTxFactory factory,
                              Keyspace keyspace, ConceptId conceptId, long shardingThreshold){
        Lock engineLock = lockProvider.getLock(getLockingKey(keyspace, conceptId));
        engineLock.lock(); //Try to get the lock

        try {
            //Check if sharding is still needed. Another engine could have sharded whilst waiting for lock
            if (incrementInstanceCountAndCheckIfShardingIsNeeded(redis, keyspace, conceptId, 0, shardingThreshold)) {


                try(EmbeddedGraknTx<?> graph = factory.tx(keyspace, GraknTxType.WRITE)) {
                    graph.shard(conceptId);
                    graph.commitSubmitNoLogs();
                }
                //Update number of shards
                redis.incrementCount(RedisCountStorage.getKeyNumShards(keyspace, conceptId), 1);
            }
        } finally {
            engineLock.unlock();
        }
    }

    private static String getLockingKey(Keyspace keyspace, ConceptId conceptId){
        return "/updating-instance-count-lock/" + keyspace + "/" + conceptId.getValue();
    }
}
