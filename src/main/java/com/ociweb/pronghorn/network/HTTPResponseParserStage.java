package com.ociweb.pronghorn.network;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.iot.schema.ClientNetResponseSchema;
import com.ociweb.pronghorn.iot.schema.NetParseAckSchema;
import com.ociweb.pronghorn.iot.schema.NetResponseSchema;
import com.ociweb.pronghorn.pipe.DataOutputBlobWriter;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.PipeWriter;
import com.ociweb.pronghorn.pipe.util.hash.IntHashTable;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.network.config.HTTPContentType;
import com.ociweb.pronghorn.stage.network.config.HTTPHeaderKey;
import com.ociweb.pronghorn.stage.network.config.HTTPHeaderKeyDefaults;
import com.ociweb.pronghorn.stage.network.config.HTTPRevision;
import com.ociweb.pronghorn.stage.network.config.HTTPSpecification;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;
import com.ociweb.pronghorn.util.TrieParser;
import com.ociweb.pronghorn.util.TrieParserReader;

public class HTTPResponseParserStage extends PronghornStage {

	private final Pipe<ClientNetResponseSchema>[] input; 
	private final Pipe<NetResponseSchema>[] output;
	private final Pipe<NetParseAckSchema> ackStop;
	private final HTTPSpecification<?,?,?,?> httpSpec;
	private final int END_OF_HEADER_ID;
	private final int UNSUPPORTED_HEADER_ID;
	
	private IntHashTable listenerPipeLookup;
	private ClientConnectionManager ccm;
	
	private static final Logger logger = LoggerFactory.getLogger(HTTPResponseParserStage.class);
	
	private static final int MAX_VALID_HEADER = 32768; //much larger than most servers using 4-8K (pipe blob must be larger than this)
	
    private final static int CHUNK_SIZE = 1;
    private final static int CHUNK_SIZE_WITH_EXTENSION = 2;
   	
	private TrieParser revisionMap;
	private TrieParser headerMap;
	private TrieParser chunkMap;
	private TrieParser typeMap;
	
	
	private TrieParserReader trieReader;
	private int[] positionMemoData;
	private long[] payloadLengthData;
	private long[] ccIdData;
	
	
	private static final int H_TRANSFER_ENCODING = 4;	
	private static final int H_CONTENT_LENGTH = 5;
	private static final int H_CONTENT_TYPE = 6;
		
	
	public HTTPResponseParserStage(GraphManager graphManager, 
			                       Pipe<ClientNetResponseSchema>[] input, 
			                       Pipe<NetResponseSchema>[] output, Pipe<NetParseAckSchema> ackStop, 
			                       IntHashTable listenerPipeLookup,
			                       ClientConnectionManager ccm,
			                       HTTPSpecification<?,?,?,?> httpSpec) {
		super(graphManager, input, join(output,ackStop));
		this.input = input;
		this.output = output;//must be 1 for each listener
		this.ccm = ccm;
		this.ackStop = ackStop;
		this.httpSpec = httpSpec;
		this.listenerPipeLookup = listenerPipeLookup;
		
		int i = input.length;
		while (--i>=0) {
			assert(	input[i].sizeOfBlobRing >=  MAX_VALID_HEADER*2 ); //size of blob ring is the largest a header can ever be.			
		}
		
		assert(this.httpSpec.headerMatches(H_TRANSFER_ENCODING, HTTPHeaderKeyDefaults.TRANSFER_ENCODING.getKey()));
		assert(this.httpSpec.headerMatches(H_CONTENT_LENGTH, HTTPHeaderKeyDefaults.CONTENT_LENGTH.getKey()));		
		assert(this.httpSpec.headerMatches(H_CONTENT_TYPE, HTTPHeaderKeyDefaults.CONTENT_TYPE.getKey()));
		
		this.UNSUPPORTED_HEADER_ID  = httpSpec.headerCount+2;
		this.END_OF_HEADER_ID       = httpSpec.headerCount+3;//for the empty header found at the bottom of the header
	}

	  @Override
	    public void startup() {
	        		  
		  positionMemoData = new int[input.length<<2];
		  payloadLengthData = new long[input.length];
		  ccIdData = new long[input.length];
		  
		  trieReader = new TrieParserReader(4);//max fields we support capturing.
		  int x;
		  
		  //HTTP/1.1 200 OK
		  //revision  status# statusString\r\n
		  //headers
		  
	      ///////////////////////////
	      //Load the supported HTTP revisions
	      ///////////////////////////
	      revisionMap = new TrieParser(256,false); //avoid deep check
	      HTTPRevision[] revs = httpSpec.supportedHTTPRevisions.getEnumConstants();
	      x = revs.length;               
	      while (--x >= 0) {
	            int b = revisionMap.setUTF8Value(revs[x].getKey(), " %u %b\n", revs[x].ordinal());   
	            revisionMap.setUTF8Value(revs[x].getKey(), " %u %b\r\n", revs[x].ordinal());
	      }
	      
	      ////////////////////////
	      //Load the supported content types
	      ////////////////////////
	      
	      typeMap = new TrieParser(4096,false);	      
	      
	      HTTPContentType[] types = httpSpec.contentTypes;
	      x = types.length;
	      while (--x >= 0) {	    	  
	    	  typeMap.setUTF8Value(types[x].contentType(),"\r\n", types[x].ordinal());	    	  
	      }
	      typeMap.setUTF8Value("%b\r\n", 0);
    	  
	      
	      
	      ///////////////////////////
	      //Load the supported header keys
	      ///////////////////////////
	      headerMap = new TrieParser(1024,false);//deep check on to detect unexpected headers.
	      HTTPHeaderKey[] shr =  httpSpec.headers;
	      x = shr.length;
	      while (--x >= 0) {
	          //must have tail because the first char of the tail is required for the stop byte
	          CharSequence key = shr[x].getKey();
	          
	          if (H_CONTENT_TYPE == x) {
	        	  key = key.subSequence(0, key.length()-2);//removes %b from the end so we can parse for it specifically
	        	  headerMap.setUTF8Value(key,shr[x].ordinal());
	          } else {
	        	   headerMap.setUTF8Value(key, "\n",shr[x].ordinal());
	        	   headerMap.setUTF8Value(key, "\r\n",shr[x].ordinal());
	          }
	      }
	      headerMap.setUTF8Value("%b: %b\n", UNSUPPORTED_HEADER_ID);
	      headerMap.setUTF8Value("%b: %b\r\n", UNSUPPORTED_HEADER_ID);	
	      
	      headerMap.setUTF8Value("\n", END_OF_HEADER_ID); //Detecting this first but not right!! we did not close the revision??
	      headerMap.setUTF8Value("\r\n", END_OF_HEADER_ID);	        
 	      
	      chunkMap = new TrieParser(128,false);
	      chunkMap.setUTF8Value("%U\r\n", CHUNK_SIZE); //hex parser of U% does not require leading 0x
	      chunkMap.setUTF8Value("%U;%b\r\n", CHUNK_SIZE_WITH_EXTENSION);
	    }
	
	  int runLength = 0;
	  
	@Override
	public void run() {
		
		//TODO: keep going untl we make a pass and there is no work.
		
		boolean foundWork;
	
		do {		
			foundWork = false;
			
			int i = input.length;
			while (--i>=0) {
				final int memoIdx = i<<2;
				final int stateIdx = memoIdx+2;
				
				Pipe<NetResponseSchema> targetPipe = null;
				long ccId = 0;
				
				Pipe<ClientNetResponseSchema> pipe = input[i];
				if (!Pipe.hasContentToRead(pipe)) {
					TrieParserReader.loadPositionMemo(trieReader, positionMemoData, memoIdx);
					if (trieReader.sourceLen<=0) {
						continue;
					} else {
						//else use the data we have
						
						ccId = ccIdData[i];
						final ClientConnection cc = ccm.get(ccId);					
						if (null==cc) {	//skip data the connection was closed						
							TrieParserReader.parseSkip(trieReader, trieReader.sourceLen);
							TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);
							continue;
						}
						if (trieReader.sourceLen>MAX_VALID_HEADER && (positionMemoData[stateIdx]==0 || positionMemoData[stateIdx]==1)) {
							
							logger.warn("bad response from server {}",cc);
							cc.close();
							
							//TODO: this may be the error case if state is 0-1 and the parser memo has data.
							
						}
						
						
						targetPipe = output[(short)IntHashTable.getItem(listenerPipeLookup, cc.getUserId())];
						
						
					}
				} else {	
									
					int msgIdx = Pipe.takeMsgIdx(pipe);
					assert(ClientNetResponseSchema.MSG_SIMPLERESPONSE_210==msgIdx): "msgIdx "+msgIdx+"  "+pipe;
			
					ccId = Pipe.takeLong(pipe);
					ccIdData[i] = ccId;
					final ClientConnection cc = ccm.get(ccId);
								
					if (null==cc) {		
						System.err.println("********************* closed connection");
						//abandon this record and continue
						Pipe.takeRingByteMetaData(pipe);
						Pipe.takeRingByteLen(pipe);
						Pipe.confirmLowLevelRead(pipe, Pipe.sizeOf(pipe, msgIdx)); 		
						Pipe.releaseReadLock(pipe);
						positionMemoData[memoIdx+1] = 0;//wipe out existing data
						continue;
					}
					
					targetPipe = output[(short)IntHashTable.getItem(listenerPipeLookup, cc.getUserId())];
					
					//ensure we have the right backing array, and mask (no position change)
					TrieParserReader.parseSetup(trieReader,Pipe.blob(pipe),Pipe.blobMask(pipe));
					//append the new data
					int meta = Pipe.takeRingByteMetaData(pipe);
					int len = Pipe.takeRingByteLen(pipe);
					int pos = Pipe.bytePosition(meta, pipe, len);
			
	
					if (positionMemoData[memoIdx+1]==0) {
						positionMemoData[memoIdx] = pos;
						positionMemoData[memoIdx+1] = len;
					} else {				
						positionMemoData[memoIdx+1] += len;
					}
					TrieParserReader.loadPositionMemo(trieReader, positionMemoData, memoIdx);
	
					Pipe.confirmLowLevelRead(pipe, Pipe.sizeOf(pipe, msgIdx));   //release of read does not happen until the bytes are consumed...
					Pipe.readNextWithoutReleasingReadLock(pipe);
				}
	
				int state = positionMemoData[stateIdx];
				
				switch (state) {
					case 0:////HTTP/1.1 200 OK              FIRST LINE REVISION AND STATUS NUMBER
						int startingLength1 = TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);
						runLength = 0;
						if (!PipeWriter.hasRoomForWrite(targetPipe)) {
							System.err.println("no room for write");
							break;
						}
						final int revisionId = (int)TrieParserReader.parseNext(trieReader, revisionMap);
						if (revisionId<0) {
									
							TrieParserReader.loadPositionMemo(trieReader, positionMemoData, memoIdx);
							
							
							if (trieReader.sourceLen>=10) { //IF BIGGER THAN MAX THIS IS AN ERROR
								
								ClientConnection connection = ccm.get(ccId);
								if (null!=connection) {
									connection.close();
								}
								
								
								//TODO: send eerror message what we have disconned.
								
								System.err.println("no revision but data is :"+trieReader.sourceLen);
								
								
								
								ByteArrayOutputStream ist = new ByteArrayOutputStream();
								
								
								trieReader.debugAsUTF8(trieReader, new PrintStream(ist), 20, false);
								
								System.err.println("'"+new String(ist.toByteArray())+"'");
								
								requestShutdown();
								return;
								
							}
							
							break;
						} else {
							
							payloadLengthData[i] = 0;//clear payload length rules, to be populated by headers
							
							{
								DataOutputBlobWriter<NetResponseSchema> writer = PipeWriter.outputStream(targetPipe);							
								writer.openField();							
								TrieParserReader.writeCapturedShort(trieReader, 0, writer); //status code
								foundWork = true;
								
							}
							
							positionMemoData[stateIdx]= ++state;
							
							runLength += (startingLength1 - trieReader.sourceLen);
							Pipe.releasePendingAsReadLock(pipe, startingLength1 - trieReader.sourceLen);					
							
						}
						
					case 1: ///////// HEADERS
						//this writer was opened when we parsed the first line, now we are appending to it.
						DataOutputBlobWriter<NetResponseSchema> writer = PipeWriter.outputStream(targetPipe);
						
						boolean foundEnd = false;
						do {
							int startingLength = TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);		

							int headerId = (int)TrieParserReader.parseNext(trieReader, headerMap);
							if (headerId>=0) {
								foundWork = true;
								if (END_OF_HEADER_ID != headerId) {
									
									//only some headers are supported the rest are ignored
									
									switch (headerId) {
										case H_TRANSFER_ENCODING:										
											payloadLengthData[i] = -1; //marked as chunking										
											break;
										case H_CONTENT_LENGTH:										
											long length = TrieParserReader.capturedLongField(trieReader, 0);
											
											if (-1 != payloadLengthData[i]) {
												payloadLengthData[i] = length;
											}
											break;
											
										//other values to write to stream?	
										case H_CONTENT_TYPE:										
											writer.writeShort((short)H_CONTENT_TYPE);
											
											int type = (int)TrieParserReader.parseNext(trieReader, typeMap);
											
									//		System.out.println("type was "+type);//TODO: refine this.
											writer.writeShort((short)type);
											break;
																											
									}
									
									//do not change state we want to come back here.
									
								} else {									
									
									//all done with header move on to body
									writer.writeShort((short)-1); //END OF HEADER FIELDS 		
									
									//Now write header message, we know there is room because we checked before starting.
									//TODO: in the future should use multiple fragments to allow for streaming response, important feature.
									PipeWriter.tryWriteFragment(targetPipe, NetResponseSchema.MSG_RESPONSE_101);
								    PipeWriter.writeLong(targetPipe, NetResponseSchema.MSG_RESPONSE_101_FIELD_CONNECTIONID_1, ccId);
	
									if (payloadLengthData[i]<0) {
										positionMemoData[stateIdx]= state= 3;	
										payloadLengthData[i] = 0;//starting chunk size.
									} else {
										positionMemoData[stateIdx]= state= 2;									
									}
									foundEnd = true;
									TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);
									
								}
								
								runLength += (startingLength - trieReader.sourceLen);
								Pipe.releasePendingAsReadLock(pipe, startingLength - trieReader.sourceLen);
								
							} else {
								//could not parse, we need more content, 
								//we will detect the overload error when we fetch new data not here.
								//continue after we get more data.
					    		break;
							}
						} while(!foundEnd);
						break;
					case 2: //PAYLOAD READING WITH LENGTH
							long lengthRemaining = payloadLengthData[i];
							DataOutputBlobWriter<NetResponseSchema> writer2 = PipeWriter.outputStream(targetPipe);
			
							int temp = TrieParserReader.parseCopy(trieReader, lengthRemaining, writer2);
							
							if (temp>0) {								
								foundWork = true;
							}
							
							lengthRemaining -= temp;
							runLength += (temp);
							Pipe.releasePendingAsReadLock(pipe, temp); 
							payloadLengthData[i] = lengthRemaining;
							TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);
							if (0 == lengthRemaining) {
								//NOTE: input is low level, TireParser is using low level take
								//      writer output is high level;									
								writer2.closeHighLevelField(NetResponseSchema.MSG_RESPONSE_101_FIELD_PAYLOAD_3);
								positionMemoData[stateIdx] = state = 5;
								PipeWriter.publishWrites(targetPipe);
								break;
							}
							break;
	
					case 3: //PAYLOAD READING WITH CHUNKS	
							long chunkRemaining = payloadLengthData[i];
							DataOutputBlobWriter<NetResponseSchema> writer3 = PipeWriter.outputStream(targetPipe);
							do {
								if (0==chunkRemaining) {
									int startingLength3 = TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);	
										
									int chunkId = (int)TrieParserReader.parseNext(trieReader, chunkMap);
									if (chunkId < 0) {
										
										//restore poisition so we can debug.
										TrieParserReader.loadPositionMemo(trieReader, positionMemoData, memoIdx);
										
										ByteArrayOutputStream ist = new ByteArrayOutputStream();
										
										int readingPos = trieReader.sourcePos;
										
										trieReader.debugAsUTF8(trieReader, new PrintStream(ist), 100,false);
	
										byte[] data = ist.toByteArray();
										
										assert (trieReader.sourceLen==0 || (  (data[0]>='0' && data[0]<='9') || (data[0]>='a' && data[0]<='f')    )) : "Non hex valu found at "+readingPos+" data: "+new String(data);
										
										
										if (trieReader.sourceLen>16) { //FORMAL ERROR
											System.err.println("chunk ID is TOO long starting at "+readingPos);
											System.err.println(new String(data));
											System.err.println(pipe);
											trieReader.debug(); //failure position is AT the mask??
											
											TrieParserReader.loadPositionMemo(trieReader, positionMemoData, memoIdx);
											int chunkId3 = (int)TrieParserReader.parseNext(trieReader, chunkMap);
											System.err.println("parsed value was "+chunkId3);
											
											requestShutdown();
											
										}
									
										return;	//not enough data yet to parse try again later
									}					
									foundWork = true;								
									chunkRemaining = TrieParserReader.capturedLongField(trieReader,0);
									
									if (0==chunkRemaining) {
										
										//TODO: Must add parse support for trailing headers!, this is a hack for now.
										int headerId = (int)TrieParserReader.parseNext(trieReader, headerMap);
										TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);
											
										runLength += (startingLength3 - trieReader.sourceLen);
										Pipe.releasePendingAsReadLock(pipe, startingLength3 - trieReader.sourceLen);
										
										
										if (/*trieReader.sourceLen!=0 ||*/ END_OF_HEADER_ID!=headerId /*|| Pipe.contentRemaining(input[i])>0*/) {
											System.err.println("ERROR "+headerId+"  "+pipe);
											TrieParserReader.loadPositionMemo(trieReader, positionMemoData, memoIdx);
											trieReader.debugAsUTF8(trieReader, System.err, 20, false);
											requestShutdown();
										}
										
										//NOTE: input is low level, TireParser is using low level take
										//      writer output is high level;									
										writer3.closeHighLevelField(NetResponseSchema.MSG_RESPONSE_101_FIELD_PAYLOAD_3);
										positionMemoData[stateIdx] = state = 5;
										PipeWriter.publishWrites(targetPipe);
										
										
										//System.err.println("at end of parse consumed "+runLength+" trieParser is at "+trieReader.sourcePos);
										
										
										break;
									} else {
										
										runLength += (startingLength3 - trieReader.sourceLen);
										Pipe.releasePendingAsReadLock(pipe, startingLength3 - trieReader.sourceLen);
										
										TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);
									}
								}							
								int temp3 = TrieParserReader.parseCopy(trieReader, chunkRemaining, writer3);
								if (temp3>0) {
									foundWork = true;
								}
									
								chunkRemaining -= temp3;
								if (chunkRemaining==0) {
									//NOTE: assert of these 2 bytes would be a good idea right here.
									TrieParserReader.parseSkip(trieReader, 2); //skip \r\n which appears on the end of every chunk
									temp3+=2;
								}
								TrieParserReader.savePositionMemo(trieReader, positionMemoData, memoIdx);
												
								runLength += (temp3);
								Pipe.releasePendingAsReadLock(pipe, temp3); 
							} while (0 == chunkRemaining);
							
							payloadLengthData[i] = chunkRemaining;	
							break;
					
					case 5: //END SEND ACK
								
						countOfRecords++;
						//System.err.println("parse records "+ countOfRecords++); //TODO: not called after last header?
						
						
						if (PipeWriter.tryWriteFragment(ackStop, NetParseAckSchema.MSG_PARSEACK_100)) {
							PipeWriter.writeLong(ackStop, NetParseAckSchema.MSG_PARSEACK_100_FIELD_CONNECTIONID_1, ccId);
							PipeWriter.publishWrites(ackStop);
							positionMemoData[stateIdx] = 0;
							foundWork = true;
							break;
						} else {
							//no room to ack so keep this state and try again later
				    		break;
						}		
						
				}
				
			}
			
		} while(foundWork);
		
	}
	
	public int countOfRecords = 0;


}
