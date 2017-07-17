package com.ociweb.oe.floglight.api;

import com.ociweb.gl.api.HTTPResponseListener;
import com.ociweb.gl.api.HTTPResponseReader;
import com.ociweb.gl.api.Payloadable;
import com.ociweb.pronghorn.network.config.HTTPContentType;
import com.ociweb.pronghorn.pipe.BlobReader;

public class HTTPResponse implements HTTPResponseListener {

	@Override
	public boolean responseHTTP(short statusCode, HTTPContentType type, HTTPResponseReader reader) {
		
		System.out.println(" status:"+statusCode);
		System.out.println("   type:"+type);
		
		//TODO: this is not yet implemented.. Under active work...
		Payloadable payload = new Payloadable() {

			@Override
			public void read(BlobReader reader) {
				// TODO Auto-generated method stub
				System.out.println(reader.readUTFOfLength(reader.available()));
				
			}
			
		};
		reader.openPayloadData(payload );
		//TODO: not sure this is the right interface for this?
		
		return true;
	}

}
