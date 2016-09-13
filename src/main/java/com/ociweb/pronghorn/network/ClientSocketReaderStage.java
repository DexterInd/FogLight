package com.ociweb.pronghorn.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.iot.schema.ClientNetRequestSchema;
import com.ociweb.pronghorn.iot.schema.ClientNetResponseSchema;
import com.ociweb.pronghorn.iot.schema.NetParseAckSchema;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.PipeReader;
import com.ociweb.pronghorn.pipe.PipeWriter;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;

public class ClientSocketReaderStage extends PronghornStage {
	
	private final ClientConnectionManager ccm;
	private final Pipe<ClientNetResponseSchema>[] output2;
	private final Pipe<NetParseAckSchema> parseAck;
	private Logger log = LoggerFactory.getLogger(ClientSocketReaderStage.class);

	
	protected ClientSocketReaderStage(GraphManager graphManager, ClientConnectionManager ccm, Pipe<NetParseAckSchema> parseAck, Pipe<ClientNetResponseSchema>[] output) {
		super(graphManager, parseAck, output);
		this.ccm = ccm;
		this.output2 = output;
		this.parseAck = parseAck;
		
		assert(ccm.resposePoolSize() == output.length);

		
	}

	@Override
	public void startup() {
		//this thread must no delay to take things out of the buffer
		//Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
	}

	@Override
	public void run() {

		try {
					
			Selector selector = ccm.selector();
			if (selector.selectNow()>0) {			
				
				Set<SelectionKey> keySet = selector.selectedKeys();
				Iterator<SelectionKey> keyIterator = keySet.iterator();
				while (keyIterator.hasNext()) {				
					SelectionKey selectionKey = keyIterator.next();
					
					if (!selectionKey.isValid()) {
						keyIterator.remove();
						System.err.println("key invalid");
						continue;
					}
					
					ClientConnection cc = (ClientConnection)selectionKey.attachment();
					
				    if (cc.isValid()) {
				    				    	
				    	int pipeIdx = ccm.responsePipeLineIdx(cc.getId());
				    	if (pipeIdx>=0) {
				    		//was able to reserve a pipe run 
					    	Pipe<ClientNetResponseSchema> target = output2[pipeIdx];
					    	
//					    	while (!PipeWriter.hasRoomForWrite(target)) {
//					    		Thread.yield();
//					    	}
					    	
					    	if (PipeWriter.hasRoomForWrite(target)) {	    	
						    	
					    		ByteBuffer[] wrappedUnstructuredLayoutBufferOpen = PipeWriter.wrappedUnstructuredLayoutBufferOpen(target,ClientNetResponseSchema.MSG_RESPONSE_200_FIELD_PAYLOAD_203);
					    							    		
					    		int readCount = 0;
						    	long temp=0;
						    	do {
									temp = cc.readfromSocketChannel(wrappedUnstructuredLayoutBufferOpen);
				
						    		if (temp>0) {
						    			readCount+=temp;
						    		} else {
						    			if (temp<0 && readCount==0) {
						    				readCount=-1;
						    			}
						    			break;
						    		}
						     	} while (true);
						    	
						    	if (readCount>0) {	
						    		//log.debug("read count from socket {} vs {} ",readCount,  wrappedUnstructuredLayoutBufferOpen.position()-p);
						    		//we read some data so send it			    					    		
						    		if (PipeWriter.tryWriteFragment(target, ClientNetResponseSchema.MSG_RESPONSE_200)) try {
						    			PipeWriter.writeLong(target, ClientNetResponseSchema.MSG_RESPONSE_200_FIELD_CONNECTIONID_201, cc.getId() );
						    			PipeWriter.wrappedUnstructuredLayoutBufferClose(target, ClientNetResponseSchema.MSG_RESPONSE_200_FIELD_PAYLOAD_203, readCount);
						    		    //log.info("from socket published          {} bytes ",readCount);
						    		} finally {
						    			PipeWriter.publishWrites(target);
						    		} else {
						    			throw new RuntimeException("Internal error");
						    		}
						    		
						    	} else {
						    		//nothing to send so let go of byte buffer.
						    		PipeWriter.wrappedUnstructuredLayoutBufferCancel(target);
						    		
						    	}
					    	} //else we try again
				    	}
				    } else {
				    	System.err.println("not valid cc closed");
				    	//try again later
				    }
				    keyIterator.remove();//always remove we will be told again next time we call for select	    	
				}	
			}

			//ack can only come back after we sent some data and therefore the key is on the set.
			//for each ack find the matching key and remove it.
			
			//TODO keep with the reader, we stop reading when we get this signal.
			while (PipeReader.tryReadFragment(parseAck)) try {

				if (PipeReader.getMsgIdx(parseAck)==NetParseAckSchema.MSG_PARSEACK_100) {				
									
					long finishedConnectionId = PipeReader.readLong(parseAck, NetParseAckSchema.MSG_PARSEACK_100_FIELD_CONNECTIONID_1);
					
					ClientConnection clientConnection = ccm.get(finishedConnectionId);
					//only remove after all the in flight messages are consumed
					if (clientConnection.incResponsesReceived()) {	
						
						//only add this cancel after we determine we it should be added.
						//clientConnection.getSelectionKey().cancel();
						
						//because this is a single thread it is the only place where we can manage the Pool of outgoing pipe runs.
						ccm.releaseResponsePipeLineIdx(clientConnection.getId());					
						
					}
				}
				
			} finally {
				PipeReader.releaseReadLock(parseAck);
			}

			
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		
		
	}

}
