package com.alibaba.datax.plugin.writer.es244writer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;

public class Es244Writer extends Writer {

	public static class Job extends Writer.Job {

		// private static final Logger LOGGER = LoggerFactory.getLogger(Job.class);

		private Configuration originalConfig = null;

		@Override
		public void init() {
			this.originalConfig = super.getPluginJobConf();
		}

		@Override
		public void destroy() {
		}

		@Override
		public List<Configuration> split(int mandatoryNumber) {
			List<Configuration> configList = new ArrayList<Configuration>();
			for (int i = 0; i < mandatoryNumber; i++) {
				configList.add(this.originalConfig.clone());
			}
			return configList;
		}
	}

	public static class Task extends Writer.Task {

		private static final Logger LOGGER = LoggerFactory.getLogger(Task.class);

		private TransportClient client;
		private Configuration writerSliceConfig;
		private String clusterName;
		private String host;
		private String index;
		private String type;
		private String pk;
		private Integer batchSize;
		private JSONArray columnMeta;

		private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ");

		@Override
		public void init() {
			this.writerSliceConfig = this.getPluginJobConf();
			this.clusterName = writerSliceConfig.getString(KeyConstant.CLUSTER_NAME);
			this.host = writerSliceConfig.getString(KeyConstant.HOST);
			this.index = writerSliceConfig.getString(KeyConstant.INDEX);
			this.type = writerSliceConfig.getString(KeyConstant.TYPE);
			this.pk = writerSliceConfig.getString(KeyConstant.PK);
			this.batchSize = writerSliceConfig.getInt(KeyConstant.BATCH_SIZE);
			this.columnMeta = JSON.parseArray(writerSliceConfig.getString(KeyConstant.COLUMN));

			if (this.batchSize == null) {
				this.batchSize = 1000;
			}

			Settings settings = Settings.builder().put("cluster.name", this.clusterName).build();

			String[] hosts = this.host.split(",");

			client = TransportClient.builder().settings(settings).build();
			for (String h : hosts) {
				String[] address = h.split(":");
				String[] ipStrs = address[0].split("\\.");
				byte[] ip = new byte[4];
				for (int i = 0; i < 4; i++) {
					ip[i] = (byte) (Integer.parseInt(ipStrs[i]) & 0xff);
				}
				int port = Integer.parseInt(address[1]);

				try {
					client = client.addTransportAddress((new InetSocketTransportAddress(InetAddress.getByAddress(ip), port)));
				} catch (UnknownHostException e) {
					LOGGER.error(e.getMessage(), e);
					throw new RuntimeException(e);
				}
			}
		}

		@Override
		public void destroy() {
			if (client != null) {
				client.close();
			}
		}

		@Override
		public void startWrite(RecordReceiver lineReceiver) {
			List<Record> writerBuffer = new ArrayList<Record>(this.batchSize);
			Record record = null;
			while ((record = lineReceiver.getFromReader()) != null) {
				writerBuffer.add(record);
				if (writerBuffer.size() >= this.batchSize) {
					batchInsert(writerBuffer, this.columnMeta);
					writerBuffer.clear();
				}
			}
			if (!writerBuffer.isEmpty()) {
				batchInsert(writerBuffer, this.columnMeta);
				writerBuffer.clear();
			}
		}

		private void batchInsert(List<Record> writerBuffer, JSONArray columnMeta) {
			try {
				BulkRequestBuilder bulkRequest = this.client.prepareBulk();

				for (Record record : writerBuffer) {
					Map<String, Object> document = new HashMap<String, Object>();

					for (int i = 0; i < record.getColumnNumber(); i++) {
						String columnType = columnMeta.getJSONObject(i).getString(KeyConstant.COLUMN_TYPE);
						String columnName = columnMeta.getJSONObject(i).getString(KeyConstant.COLUMN_NAME);

						// TODO Anders decimal有精度问题，es不支持该数据类型
						if (("decimal").equalsIgnoreCase(columnType)) {
							Column col = record.getColumn(i);

							if (col == null || col.asBigDecimal() == null) {
								document.put(columnName, null);
							} else {
								document.put(columnName, col.asBigDecimal().doubleValue());
							}
						} else if (columnType.equalsIgnoreCase(Column.Type.DATE.name())) {
							Column col = record.getColumn(i);

							if (col == null || col.asDate() == null) {
								document.put(columnName, null);
							} else {
								document.put(columnName, dateFormat.format(col.asDate()));
							}
						} else if (columnType.equalsIgnoreCase(Column.Type.LONG.name())) {
							Column col = record.getColumn(i);

							if (col == null || col.asLong() == null) {
								document.put(columnName, null);
							} else {
								document.put(columnName, col.asLong());
							}
						} else if (columnType.equalsIgnoreCase(Column.Type.INT.name())) {
							Column col = record.getColumn(i);

							if (col == null || col.asLong() == null) {
								document.put(columnName, null);
							} else {
								document.put(columnName, col.asLong().intValue());
							}
						} else if (("short").equalsIgnoreCase(columnType)) {
							Column col = record.getColumn(i);

							if (col == null || col.asLong() == null) {
								document.put(columnName, null);
							} else {
								document.put(columnName, col.asLong().shortValue());
							}
						} else if (columnType.equalsIgnoreCase(Column.Type.DOUBLE.name())) {
							Column col = record.getColumn(i);

							if (col == null || col.asDouble() == null) {
								document.put(columnName, null);
							} else {
								document.put(columnName, col.asDouble());
							}
						} else if (("float").equalsIgnoreCase(columnType)) {
							Column col = record.getColumn(i);

							if (col == null || col.asDouble() == null) {
								document.put(columnName, null);
							} else {
								document.put(columnName, col.asDouble().floatValue());
							}
						} else {
							document.put(columnName, record.getColumn(i).getRawData());
						}
					}
					bulkRequest.add(client.prepareIndex(this.index, this.type, document.get(this.pk).toString()).setSource(document));
				}

				BulkResponse bulkResponse = bulkRequest.get();

				LOGGER.debug("bulk size : {}", bulkResponse.getItems().length);

				if (bulkResponse.hasFailures()) {
					throw new RuntimeException(bulkResponse.buildFailureMessage());
				}
			} catch (Exception e) {
				LOGGER.error("failed to bulk insert", e);
			}
		}
	}
}
