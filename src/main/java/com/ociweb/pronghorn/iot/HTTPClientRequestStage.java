package com.ociweb.pronghorn.iot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.iot.hardware.HardwareImpl;
import com.ociweb.pronghorn.iot.schema.TrafficAckSchema;
import com.ociweb.pronghorn.iot.schema.TrafficReleaseSchema;
import com.ociweb.pronghorn.network.ClientConnection;
import com.ociweb.pronghorn.network.ClientConnectionManager;
import com.ociweb.pronghorn.pipe.DataOutputBlobWriter;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.PipeReader;
import com.ociweb.pronghorn.pipe.PipeWriter;
import com.ociweb.pronghorn.schema.ClientNetRequestSchema;
import com.ociweb.pronghorn.schema.NetRequestSchema;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;

public class HTTPClientRequestStage extends AbstractTrafficOrderedStage {

	public static final Logger log = LoggerFactory.getLogger(HTTPClientRequestStage.class);
	
	private final Pipe<NetRequestSchema>[] input;
	private final Pipe<ClientNetRequestSchema>[] output;
	private final ClientConnectionManager ccm;

	private int activeOutIdx = 0;
			
	private static final String implementationVersion = PronghornStage.class.getPackage().getImplementationVersion()==null?"unknown":PronghornStage.class.getPackage().getImplementationVersion();
		
	private StringBuilder activeHost;
	
	/**
	 * Parse HTTP data on feed and sends back an ack to the  SSLEngine as each message is decrypted.
	 * 
	 * @param graphManager
	 * @param hardware
	 * @param input
	 * @param goPipe
	 * @param ackPipe
	 * @param output
	 */
	
	public HTTPClientRequestStage(GraphManager graphManager, 
			HardwareImpl hardware,
			ClientConnectionManager ccm,
            Pipe<NetRequestSchema>[] input,
            Pipe<TrafficReleaseSchema>[] goPipe,
            Pipe<TrafficAckSchema>[] ackPipe,
            Pipe<ClientNetRequestSchema>[] output
            ) {
		super(graphManager, hardware, input, goPipe, ackPipe, output);
		this.input = input;
		this.output = output;
		this.ccm = ccm;
		
	}
	
	
	@Override
	public void startup() {
		super.startup();		
		this.activeHost = new StringBuilder();
	}
	
	@Override
	public void shutdown() {		
		this.ccm.shutdown();
	}
	
	@Override
	protected void processMessagesForPipe(int activePipe) {
		
		    Pipe<NetRequestSchema> requestPipe = input[activePipe];
		    	        	    
		    
	        while (PipeReader.hasContentToRead(requestPipe) && hasReleaseCountRemaining(activePipe) 
	                && isChannelUnBlocked(activePipe)	                
	                && hasRoomForWrite(requestPipe, activeHost, output, ccm)
	                && PipeReader.tryReadFragment(requestPipe) ){
	  	    
	        	
	        	//Need peek to know if this will block.
	        	
	            int msgIdx = PipeReader.getMsgIdx(requestPipe);
	            
				switch (msgIdx) {
	            			case NetRequestSchema.MSG_HTTPGET_100:
	            				
	            				activeHost.setLength(0);//NOTE: we may want to think about a zero copy design
				                PipeReader.readUTF8(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_HOST_2, activeHost);
				                {
					                int port = PipeReader.readInt(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_PORT_1);
					                int userId = PipeReader.readInt(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_LISTENER_10);
					                
					                long connectionId = ccm.lookup(activeHost, port, userId);	
					                
					                if (-1 != connectionId) {
						                
					                	ClientConnection clientConnection = ccm.get(connectionId);
					                	int outIdx = clientConnection.requestPipeLineIdx();
					                	
					                	clientConnection.incRequestsSent();//count of messages can only be done here.
										Pipe<ClientNetRequestSchema> outputPipe = output[outIdx];
						                				                	
						                if (PipeWriter.tryWriteFragment(outputPipe, ClientNetRequestSchema.MSG_SIMPLEREQUEST_100) ) {
						                    	
						                	PipeWriter.writeLong(outputPipe, ClientNetRequestSchema.MSG_SIMPLEREQUEST_100_FIELD_CONNECTIONID_101, connectionId);
						                	
						                	DataOutputBlobWriter<ClientNetRequestSchema> activeWriter = PipeWriter.outputStream(outputPipe);
						                	DataOutputBlobWriter.openField(activeWriter);
											
						                	DataOutputBlobWriter.encodeAsUTF8(activeWriter,"GET");
						                	
						                	int len = PipeReader.readDataLength(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_PATH_3);					                	
						                	int  first = PipeReader.readBytesPosition(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_PATH_3);					                	
						                	boolean prePendSlash = (0==len) || ('/' != PipeReader.readBytesBackingArray(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_PATH_3)[first&Pipe.blobMask(requestPipe)]);  
						                	
											if (prePendSlash) { //NOTE: these can be pre-coverted to bytes so we need not convert on each write. may want to improve.
												DataOutputBlobWriter.encodeAsUTF8(activeWriter," /");
											} else {
												DataOutputBlobWriter.encodeAsUTF8(activeWriter," ");
											}
											
											//Reading from UTF8 field and writing to UTF8 encoded field so we are doing a direct copy here.
											PipeReader.readBytes(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_PATH_3, activeWriter);
											
											finishWritingHeader(activeHost, activeWriter, implementationVersion);
						                	DataOutputBlobWriter.closeHighLevelField(activeWriter, ClientNetRequestSchema.MSG_SIMPLEREQUEST_100_FIELD_PAYLOAD_103);
						                					                	
						                	PipeWriter.publishWrites(outputPipe);
						                					                	
						                } else {
						                	System.err.println("unable to write");
						                	throw new RuntimeException("Unable to send request, outputPipe is full");
						                }
					                }
			                	}
	            		break;
	            			case NetRequestSchema.MSG_HTTPPOST_101:
	            				
	            				
	            				activeHost.setLength(0);//NOTE: we may want to think about a zero copy design
				                PipeReader.readUTF8(requestPipe, NetRequestSchema.MSG_HTTPPOST_101_FIELD_HOST_2, activeHost);
				                {
					                int port = PipeReader.readInt(requestPipe, NetRequestSchema.MSG_HTTPPOST_101_FIELD_PORT_1);
					                int userId = PipeReader.readInt(requestPipe, NetRequestSchema.MSG_HTTPPOST_101_FIELD_LISTENER_10);
					                
					                long connectionId = ccm.lookup(activeHost, port, userId);	
					                //openConnection(activeHost, port, userId, outIdx);
					                
					                if (-1 != connectionId) {
						                
					                	ClientConnection clientConnection = ccm.get(connectionId);
					                	int outIdx = clientConnection.requestPipeLineIdx();
					                					                  	
					                	clientConnection.incRequestsSent();//count of messages can only be done here.
										Pipe<ClientNetRequestSchema> outputPipe = output[outIdx];
					                
						                if (PipeWriter.tryWriteFragment(outputPipe, ClientNetRequestSchema.MSG_SIMPLEREQUEST_100) ) {
					                    	
						                	PipeWriter.writeLong(outputPipe, ClientNetRequestSchema.MSG_SIMPLEREQUEST_100_FIELD_CONNECTIONID_101, connectionId);
						                	
						                	DataOutputBlobWriter<ClientNetRequestSchema> activeWriter = PipeWriter.outputStream(outputPipe);
						                	DataOutputBlobWriter.openField(activeWriter);
						                			                
						                	DataOutputBlobWriter.encodeAsUTF8(activeWriter,"POST");
						                	
						                	int len = PipeReader.readDataLength(requestPipe, NetRequestSchema.MSG_HTTPPOST_101_FIELD_PATH_3);					                	
						                	int  first = PipeReader.readBytesPosition(requestPipe, NetRequestSchema.MSG_HTTPPOST_101_FIELD_PATH_3);					                	
						                	boolean prePendSlash = (0==len) || ('/' != PipeReader.readBytesBackingArray(requestPipe, NetRequestSchema.MSG_HTTPPOST_101_FIELD_PATH_3)[first&Pipe.blobMask(requestPipe)]);  
						                	
											if (prePendSlash) { //NOTE: these can be pre-coverted to bytes so we need not convert on each write. may want to improve.
												DataOutputBlobWriter.encodeAsUTF8(activeWriter," /");
											} else {
												DataOutputBlobWriter.encodeAsUTF8(activeWriter," ");
											}
											
											//Reading from UTF8 field and writing to UTF8 encoded field so we are doing a direct copy here.
											PipeReader.readBytes(requestPipe, NetRequestSchema.MSG_HTTPPOST_101_FIELD_PATH_3, activeWriter);
											
											finishWritingHeader(activeHost, activeWriter, implementationVersion);
											
											//TODO: must write lenghth in header before we write the payload.
											//un-tested  post payload here, TODO: need to add support for chunking and length??
											PipeReader.readBytes(requestPipe, NetRequestSchema.MSG_HTTPPOST_101_FIELD_PAYLOAD_5, activeWriter);
											
						                	DataOutputBlobWriter.closeHighLevelField(activeWriter, ClientNetRequestSchema.MSG_SIMPLEREQUEST_100_FIELD_PAYLOAD_103);
						                					                	
						                	PipeWriter.publishWrites(outputPipe);
						                					                	
						                } else {
						                	System.err.println("unable to write");
						                	throw new RuntimeException("Unable to send request, outputPipe is full");
						                }
										
										
										
										
					                }
		            		
				                }
	    	        	break;
	    	            
	            	
	            
	            }
			
				PipeReader.releaseReadLock(requestPipe);
				
				//only do now after we know its not blocked and was completed
				decReleaseCount(activePipe);
	        }

	            
		
	}


	//TODO: make static.
	public boolean hasRoomForWrite(Pipe<NetRequestSchema> requestPipe, StringBuilder activeHost, Pipe<ClientNetRequestSchema>[] output, ClientConnectionManager ccm) {
		int result = -1;
		//if we go around once and find nothing then stop looking
		int i = output.length;
		while (--i>=0) {
			//next idx		
			if (++activeOutIdx == output.length) {
				activeOutIdx = 0;
			}
			//does this one have room
			if (PipeWriter.hasRoomForWrite(output[activeOutIdx])) {
				result = activeOutIdx;
				break;
			}
		}
		int outIdx = result;
		if (-1 == outIdx) {
			return false;
		}
		activeHost.setLength(0);//NOTE: we may want to think about a zero copy design
		PipeReader.peekUTF8(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_HOST_2, activeHost);
		
		int port = PipeReader.peekInt(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_PORT_1);
		int userId = PipeReader.peekInt(requestPipe, NetRequestSchema.MSG_HTTPGET_100_FIELD_LISTENER_10);
				
		long connectionId = ClientConnectionManager.openConnection(ccm, activeHost, port, userId, outIdx);
		if (-1 != connectionId) {
			ClientConnection clientConnection = ccm.get(connectionId);
			//if we have a pre-existing pipe, must use it.
			outIdx = clientConnection.requestPipeLineIdx();
			if (!PipeWriter.hasRoomForWrite(output[outIdx])) {
				return false;
			}
		}
		return true;
	}

	public static void finishWritingHeader(CharSequence host, DataOutputBlobWriter<ClientNetRequestSchema> writer, CharSequence implementationVersion) {
		DataOutputBlobWriter.encodeAsUTF8(writer," HTTP/1.1\r\nHost: ");
		DataOutputBlobWriter.encodeAsUTF8(writer,host);
		DataOutputBlobWriter.encodeAsUTF8(writer,"\r\nUser-Agent: Pronghorn/");
		DataOutputBlobWriter.encodeAsUTF8(writer,implementationVersion);
		DataOutputBlobWriter.encodeAsUTF8(writer,"\r\nConnection: keep-alive\r\n\r\n"); //double \r\b marks the end of the header
	}
	
	
	

}
