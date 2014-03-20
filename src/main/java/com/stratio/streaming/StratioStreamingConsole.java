package com.stratio.streaming;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import jline.ConsoleReader;
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.zmatrix.cli.ParseCmd;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.stratio.streaming.common.StratioStreamingConstants;
import com.stratio.streaming.messages.BaseStreamingMessage;
import com.stratio.streaming.messages.ColumnNameTypeValue;

public class StratioStreamingConsole {
	
	
	private static Logger logger = LoggerFactory.getLogger(StratioStreamingConsole.class);
	private String brokerList;
	private String sessionId;
	private Producer<String, String> producer;
	private CuratorFramework  client;
	



    public static void main(String[] args) throws Exception {

    	StratioStreamingConsole self = new StratioStreamingConsole();
    	
    	ConsoleReader reader = new ConsoleReader();
        reader.setDefaultPrompt("stratio_streaming>");
        reader.setBellEnabled(false);
        
        self.start(args);
       

        String line;
        PrintWriter out = new PrintWriter(System.out);

        while ((line = reader.readLine("stratio_streaming>")) != null) {
            
            if(line.trim().equals("")) {
        		continue;
        	}                        
            if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
            	self.shutDown();
                break;
            }
            
            line = line.toLowerCase();
            
            if (line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.ACTION.LISTEN.toLowerCase()) 
            		|| line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.ACTION.SAVETO_CASSANDRA.toLowerCase()) 
            		|| line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.ACTION.SAVETO_DATACOLLECTOR.toLowerCase()) 
            		|| line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.ADD_QUERY.toLowerCase()) 
            		|| line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.ALTER.toLowerCase()) 
            		|| line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.CREATE.toLowerCase()) 
            		|| line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.DROP.toLowerCase())
            		|| line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.INSERT.toLowerCase())
            		|| line.startsWith(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.LIST.toLowerCase())) {
            	
            	self.handleCommand(line);
            	continue;
            }
            
            
            
            
            out.println("======> Hey, i don't what to do with -> " + line);
            out.flush();
            
        }
    }
    
    
	private void start(String[] args) throws Exception {
		
//		DECODING ARGUMENTS FROM COMMAND LINE
		String usage = "usage: --broker-list ip:port";
        ParseCmd cmd = new ParseCmd.Builder()
        							.help(usage)                          
        							.parm("--broker-list", "255.25.25.255:9999" ).rex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{1,4}$").req()
        							.parm("--zookeeper",   "255.25.25.255:9999" ).rex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{1,4}$").req()
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
        
        
        this.brokerList = R.get("--broker-list").toString();
        this.sessionId = "" + System.currentTimeMillis();
        this.producer = new Producer<String, String>(createProducerConfig());
        
        
        
//		ZOOKEPER CONNECTION
		client = CuratorFrameworkFactory.newClient(R.get("--zookeeper"), 25*1000, 10*1000, new ExponentialBackoffRetry(1000, 3));
		

		client.start();
		client.getZookeeperClient().blockUntilConnectedOrTimedOut();
		
		if (!client.isStarted()) {
			 throw new Exception("Connection to Zookeeper timed out after seconds");
		}
		
		client.getCuratorListenable().addListener(new CuratorListener() {

			public void eventReceived(CuratorFramework client, CuratorEvent event) throws Exception {
//				client.getChildren().watched().forPath("/test/test");
				
				
				switch (event.getType()) {
				case WATCHED:
					System.out.println("<<<<<<<<<< REPLY FROM STRATIO STREAMING FOR REQUEST ID: " + decodeReplyFromStratioStreaming(Integer.valueOf(new String(client.getData().forPath(event.getPath())))));
					break;
					
				case CLOSING:
					System.out.println("<<<<<<<<<< SHUTTING DOWN ZK LISTENER");
					break;

				default:
					System.out.println(event.getType() + " Unknown reply from stratio streaming");
				}
				
			}
			
		});
		
        
		System.out.println(">>>>>> Connected to Stratio Bus");
        System.out.println(">>>>>> Your Session ID in Stratio Streaming is " + this.sessionId);
	}
	
	private void shutDown() {
		producer.close();
		client.close();
		System.out.println("<<<<<<<<<< SHUTTING DOWN STRATIO BUS CONNECTION");
	}
    
    
    
	private ProducerConfig createProducerConfig() {
		Properties properties = new Properties();
		properties.put("serializer.class", "kafka.serializer.StringEncoder");
		properties.put("metadata.broker.list", brokerList);
//		properties.put("request.required.acks", "1");
//		properties.put("compress", "true");
//		properties.put("compression.codec", "gzip");
//		properties.put("producer.type", "sync");
 
        return new ProducerConfig(properties);
    }  
	
	
	private void handleCommand(String request) {
		
		
		try {
			BaseStreamingMessage message = MessageFactory.getMessageFromCommand(request, sessionId);
			
			
			
			
			System.out.println("==> Sending message to Stratio Streaming: " + new Gson().toJson(message));
			KeyedMessage<String, String> busMessage = new KeyedMessage<String, String>(StratioStreamingConstants.BUS.TOPICS, request.split("@")[0].trim(), new Gson().toJson(message));
			producer.send(busMessage);
			
			
			client.checkExists().watched().forPath(StratioStreamingConstants.REPLY_CODES.ZK_BASE_PATH + "/" + message.getOperation() + "/" + message.getRequest_id());						
			
			
		} catch (Exception e) {
			System.out.println("Oooooooops, can't handle your command, maybe this can help: " + e.getMessage());
		}
	}
	
	
	private String decodeReplyFromStratioStreaming(Integer code) {
		
		String decodedReply = "";
		
		switch (code) {
		case 1:
			decodedReply = "OK";
			break;
		case 2:
			decodedReply = "KO: PARSER ERROR";
			break;
		case 3:
			decodedReply = "KO: STREAM ALREADY EXISTS";
			break;
		case 4:
			decodedReply = "KO: STREAM DOES NOT EXIST";
			break;
		case 5:
			decodedReply = "KO: QUERY ALREADY EXISTS";
			break;
		case 6:
			decodedReply = "KO: LISTENER ALREADY EXISTS";
			break;		
		case 7:
			decodedReply = "KO: GENERAL ERROR";
			break;				
		case 8:
			decodedReply = "KO: COLUMN ALREADY EXISTS";
			break;							
		case 9:
			decodedReply = "KO: COLUMN DOES NOT EXIST";
			break;	
		default:
			break;
		}
		
		
		return decodedReply;
	}
	
	
	
	
	
	private static class MessageFactory {
		
		private MessageFactory() {
			
		}
		
		
		private static BaseStreamingMessage getMessageFromCommand(String command, String sessionId) {
			
			String operation = command.split("@")[0].trim().replaceAll("\\s+","");
			
			
			if (!(operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.ALTER)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.CREATE)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.INSERT)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.ADD_QUERY)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.DROP)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.ACTION.LISTEN)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.LIST))) {
				
				throw new IllegalArgumentException("Unsupported command: " + command);
			}
		
			
			if ((operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.ALTER)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.CREATE)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.INSERT)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.ADD_QUERY))
				&& 	command.split("@").length != 3) {
				
				throw new IllegalArgumentException("Malformed request, missing or exceding parts: " + command);
			}
			
			if ((operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.DROP)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.ACTION.SAVETO_CASSANDRA)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.ACTION.SAVETO_DATACOLLECTOR)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.ACTION.LISTEN))
				&& command.split("@").length != 2) {
				
				throw new IllegalArgumentException("Malformed request, missing or exceding parts: " + command);
			}
			
			if (operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.LIST)
					&& command.split("@").length != 1) {
				throw new IllegalArgumentException("Malformed request, missing or exceding parts: " + command);
			}
			
			
			BaseStreamingMessage message = new BaseStreamingMessage();
			String request = "";
			String stream = "";
			
			
			
			if (command.split("@").length != 1) {
				stream  = command.split("@")[1].trim().replaceAll("\\s+","");
			}
			
			if (command.split("@").length == 3) {
				request = StringUtils.removeEnd(StringUtils.removeStart(command.split("@")[2].trim(), "("), ")");
			}
			
			
			if (operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.ALTER)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.CREATE)
					|| operation.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.INSERT)) {
					
				message.setColumns(decodeColumns(operation, request));
			}

			message.setRequest(request.trim());
			message.setOperation(operation);
			message.setStreamName(stream);
			message.setRequest_id("" + System.currentTimeMillis());
			message.setSession_id(sessionId);
			message.setTimestamp(System.currentTimeMillis());
			
			return message;
			
		}
		
		
		
		
		
		private static List<ColumnNameTypeValue> decodeColumns(String command, String request) {
			
			List<ColumnNameTypeValue> decodedColumns = Lists.newArrayList();
			String[] columns = request.split(",");
			
			if (columns.length == 0) {
				throw new IllegalArgumentException("No columns found");
			}
			
			
			for (String column : columns) {
				
				if (column.split("\\.").length <= 1) {
					throw new IllegalArgumentException("Error parsing columns");
				}
				
				String firstPart = column.split("\\.")[0].trim().replaceAll("\\s+","");
				String secondPart = column.split("\\.")[1].trim().replaceAll("\\s+","");
				
				if (command.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.CREATE) 
						|| command.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.DEFINITION.ALTER)) {
					
					decodedColumns.add(new ColumnNameTypeValue(firstPart, secondPart, null));
				}
				if (command.equalsIgnoreCase(StratioStreamingConstants.STREAM_OPERATIONS.MANIPULATION.INSERT)) {
					decodedColumns.add(new ColumnNameTypeValue(firstPart, null, secondPart));
				}
				
				
			}
			
			return decodedColumns;
		}	
		
		
		
	}
	
	
	

	
	
	
}
	