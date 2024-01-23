package com.github.armedis.http.service.stats;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.armedis.redis.connection.pool.RedisConnectionPool;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;

/**
 * Redis cluster node status info command result --> redis status
 */
@Component
public class RedisStatInfoBucket {
	CircularFifoQueue<String> queue = new CircularFifoQueue<>(20);

	public ObjectNode getStats() {
		queue.poll();

		return null;
	}

	@Bean(name = "executor")
	public TaskExecutor taskExecutor() {
		return new SimpleAsyncTaskExecutor();
	}

	@Bean
	public CommandLineRunner schedulingRunner(TaskExecutor executor, Thread scheduleStatRunner) {
		return args -> {
			executor.execute(scheduleStatRunner);
		};
	}

	@Bean(name = "scheduleStatRunner")
	public Thread scheduleStatRunner() {
		return new Thread() {
			List<RedisClusterNodeInfo> redisNodeInfoList;
			BlockingQueue<String> tempQueue = new LinkedBlockingQueue<>(1);

			@Autowired
			private RedisConnectionPool<String, String> redisConnectionPool;

			// initialize Redis cluster node info when server startup.
			public void run() {
				String clusterNodes = getClusterNodesCommandResult(redisConnectionPool);

				while (true) {
					redisNodeInfoList = convertToNodeInfoList(clusterNodes);

					for (RedisClusterNodeInfo redisNodeInfo : redisNodeInfoList) {
						try {
							StatefulRedisClusterConnection<String, String> connection = redisConnectionPool
									.getClusterConnection();
							StatefulRedisConnection<String, String> nodeConnection = connection
									.getConnection(redisNodeInfo.id());

							String info = nodeConnection.sync().info();
							redisConnectionPool.returnObject(connection);

//							RedisInfo RedisInfoResultConverter.convert(info);
							
							System.out.println(info);
							System.out.println(redisNodeInfo.id());
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}

					try {
						tempQueue.poll(1, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}

			private String getClusterNodesCommandResult(RedisConnectionPool<String, String> redisConnectionPool) {
				String nodes = null;
				try {
					StatefulRedisClusterConnection<String, String> connection = redisConnectionPool
							.getClusterConnection();
					nodes = connection.sync().clusterNodes();
					redisConnectionPool.returnObject(connection);
				} catch (Exception e) {
					e.printStackTrace();
				}

				return nodes;
			}

			private List<RedisClusterNodeInfo> convertToNodeInfoList(String clusterNodes) {
				List<RedisClusterNodeInfo> redisNodeInfo = new ArrayList<RedisClusterNodeInfo>();
				try {
					List<String> nodeInfoStrings = IOUtils.readLines(new StringReader(clusterNodes));

					for (String nodeInfoString : nodeInfoStrings) {
						RedisClusterNodeInfo nodeInfo = RedisClusterNodeInfoConverter.convert(nodeInfoString);

						redisNodeInfo.add(nodeInfo);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}

				return redisNodeInfo;
			}
		};
	}
}
