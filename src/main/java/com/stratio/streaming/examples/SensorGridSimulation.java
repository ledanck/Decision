package com.stratio.streaming.examples;

import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;
import ca.zmatrix.cli.ParseCmd;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.stratio.streaming.common.StratioStreamingConstants;
import com.stratio.streaming.messages.BaseStreamingMessage;
import com.stratio.streaming.messages.ColumnNameTypeValue;
import com.stratio.streaming.utils.DataToCollector;

public class SensorGridSimulation {


	
	

	private static Logger logger = LoggerFactory.getLogger(SensorGridSimulation.class);
	
	public static final String[] sensors 	= {"Temperature", "Humidity", "Carbon dioxide", "Pressure", "Oxygen", "Anemometer"};
	public static final String[] dataRanges = {"20,30", "60,75", "0,1", "50,65", "88,100", "3,16"};
	public static final String[] topics		= {"commonSensor", "commonSensor", "commonSensor", "commonSensor", "commonSensor", "commonSensor"};
//	public static final String[] ids		= {"3", "4", "5", "6", "7", "8"};  //thingspeak
	public static final String[] ids		= {"52516", "52519", "52520", "52521", "52522", "52523"};  //open.sen.se
	public static final String   sensorDataStream = "sensorgrid";
	public static final long     streamSessionId  = System.currentTimeMillis();	

	public SensorGridSimulation() {
		// TODO Auto-generated constructor stub
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		SensorGridSimulation mySelf = new SensorGridSimulation();
		try {
			mySelf.launchSensorStreaming(args);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
	
	protected void launchSensorStreaming(String[] args) throws Exception {
//		DECODING ARGUMENTS FROM COMMAND LINE
		String usage = "usage: --broker-list ip:port --events n";
        ParseCmd cmd = new ParseCmd.Builder()
        							.help(usage)                          
        							.parm("--broker-list", "255.25.25.255:9999" ).rex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{1,4}$").req()    
        							.parm("--events", "1000" ).req()
        							.build();  
       
        HashMap<String, String> R = new HashMap<String,String>();
        String parseError    = cmd.validate(args);
        if( cmd.isValid(args) ) {
            R = (HashMap<String, String>) cmd.parse(args);
            logger.info("Launching with these params:"); 
            logger.info(cmd.displayMap(R));
        }
        else { 
        	logger.error(parseError); 
        	System.exit(1); 
        } 
        
        
        launchStreamingSensorsData(R.get("--broker-list").toString(), Integer.parseInt(R.get("--events")));
        
	}
	
	private void launchStreamingSensorsData(String brokerList, int dataToGenerate) throws Exception {
		
		CountDownLatch shutdownLatch = new CountDownLatch(sensors.length);
		AtomicInteger  globalMessagesSent = new AtomicInteger(0);
		
		
//		LAUNCH THROUGHPUT SERVICE
		ExecutorService throughputExecutorService = Executors.newFixedThreadPool(1);		
		throughputExecutorService.execute(new ThroughputThread(globalMessagesSent));
		
		
//		LAUNCH SENSOR DATA SERVICES
		ExecutorService sensorExecutorService = Executors.newFixedThreadPool(sensors.length);
		
	
		long startMs = System.currentTimeMillis();		

		for (int i=0; i<sensors.length; i++) {
			sensorExecutorService.execute(new SensorThread(brokerList, sensors[i], Integer.parseInt(ids[i]), dataRanges[i], topics[i], dataToGenerate, shutdownLatch, globalMessagesSent));
		}
		
		shutdownLatch.await();		
		sensorExecutorService.shutdown();
		throughputExecutorService.shutdownNow();
    	
		 logger.info("====> TOTAL THREADS:" + sensors.length + "// TOTAL MESSAGES:" + (dataToGenerate * sensors.length) + "//Time:" + TimeUnit.MILLISECONDS.toMillis(System.currentTimeMillis() - startMs) + "//Messages/second:" + ((dataToGenerate * sensors.length) / TimeUnit.MILLISECONDS.toMillis(System.currentTimeMillis() - startMs)));
    	
	}
	

	
	
	
	protected class SensorThread implements Runnable {
		
		private String name;
		private int dataRangeLow;
		private int dataRangeHigh;
		private String topic;
		private String brokerList;
		private Producer<String, String> producer;
		private Random random;
		private int dataToGenerate;
		private int index;
		private CountDownLatch shutdownLatch;
		private AtomicInteger globalMessagesSent;
		private Gson gson;
		
		public SensorThread(String brokerList, String name, int index, String dataRange, String topic, 
									int dataToGenerate, CountDownLatch shutdownLatch, AtomicInteger globalMessagesSent) {
			this.name = name;
			this.dataRangeLow = Integer.parseInt(dataRange.split(",")[0]);
			this.dataRangeHigh = Integer.parseInt(dataRange.split(",")[1]);
			this.topic = topic;
			this.brokerList = brokerList;
			this.producer = new Producer<String, String>(createProducerConfig());	
			this.dataToGenerate = dataToGenerate;
			this.shutdownLatch = shutdownLatch;
			this.globalMessagesSent = globalMessagesSent;
			this.index = index;
			random = new Random();
			gson = new Gson();
		}
		
		private ProducerConfig createProducerConfig() {
			Properties properties = new Properties();
			properties.put("serializer.class", "kafka.serializer.StringEncoder");
			properties.put("metadata.broker.list", brokerList);
//			properties.put("request.required.acks", "1");
//			properties.put("compress", "true");
//			properties.put("compression.codec", "gzip");
//			properties.put("producer.type", "sync");
	 
	        return new ProducerConfig(properties);
	    }

		@Override
		public void run() {
			
			logger.debug(name + index + " is ON... generating " + dataToGenerate + " measures");
			 
			BaseStreamingMessage message = new BaseStreamingMessage();
			 
			 
				
			message.setOperation(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.INSERT);
			message.setStreamName(sensorDataStream);
			message.setTimestamp(System.currentTimeMillis());
			message.setSession_id("" + streamSessionId);
			
			
			
			for (int i=0; i<dataToGenerate; i++) {
				
				List<ColumnNameTypeValue> sensorData = Lists.newArrayList();
				sensorData.add(new ColumnNameTypeValue("name", null, name));
				sensorData.add(new ColumnNameTypeValue("index", null, index));
				sensorData.add(new ColumnNameTypeValue("data", null, (random.nextInt((dataRangeHigh - dataRangeLow) + 1) + dataRangeLow)));
				
				message.setRequest_id(""+ System.currentTimeMillis());				
				message.setColumns(sensorData);
				message.setRequest("dummy request");
			
				KeyedMessage<String, String> busMessage = new KeyedMessage<String, String>(StratioStreamingConstants.BUS.TOPICS, 
																								StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.INSERT, 
																								gson.toJson(message));
				producer.send(busMessage);
				globalMessagesSent.getAndIncrement();
				
			}
			
			shutdownLatch.countDown();
			producer.close();
			
		}
	}
	
	
	protected class ThroughputThread implements Runnable {
		
		private AtomicInteger  globalMessagesSent;
		
		public ThroughputThread(AtomicInteger  globalMessagesSent) {
			this.globalMessagesSent = globalMessagesSent;
		}

		@Override
		public void run() {
			
			long startTime = System.currentTimeMillis();
	  		
	        List<Tuple2<String, Object>> data = Lists.newArrayList();
			
			try {
				
				
		        
		        while (!Thread.currentThread().isInterrupted()) {
		        	
		        	data.clear();
		        	long throughput = (globalMessagesSent.get() == 0) ? 0 : globalMessagesSent.get() / TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime);
					
					
					data.add(new Tuple2<String, Object>("52734", Double.valueOf(throughput)));
					DataToCollector.sendDataToOpenSense(data);
					
					Thread.currentThread().sleep(7000);
					
		        }
		        
				
			} 
			
			catch (InterruptedException ie) {
				
				try {
					logger.debug("Ending throughput controller" + globalMessagesSent.get());
					
					data.clear();
		        	long throughput = (globalMessagesSent.get() == 0) ? 0 : globalMessagesSent.get() / TimeUnit.MILLISECONDS.toMillis(System.currentTimeMillis() - startTime);
					
					
					data.add(new Tuple2<String, Object>("52734", Double.valueOf(throughput)));
					
					try {
						DataToCollector.sendDataToOpenSense(data);
					} catch (Exception e) {
						e.printStackTrace();
					}
								
					long current = System.currentTimeMillis();
	
	
					while(System.currentTimeMillis()<(current + 1000)) {
						
					} 
					
					data.clear();
					data.add(new Tuple2<String, Object>("52734", Double.valueOf(0)));
					
					
						DataToCollector.sendDataToOpenSense(data);
					
				} catch (Exception e) {
					e.printStackTrace();
				}				
			}
			catch (Exception e) {
				e.printStackTrace();
			}
			
			
		}
		
	}


}
