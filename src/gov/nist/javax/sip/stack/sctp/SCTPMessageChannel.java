package gov.nist.javax.sip.stack.sctp;

import gov.nist.core.ServerLogger;
import gov.nist.javax.sip.header.CSeq;
import gov.nist.javax.sip.header.CallID;
import gov.nist.javax.sip.header.From;
import gov.nist.javax.sip.header.RequestLine;
import gov.nist.javax.sip.header.StatusLine;
import gov.nist.javax.sip.header.To;
import gov.nist.javax.sip.header.Via;
import gov.nist.javax.sip.message.SIPMessage;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.parser.ParseExceptionListener;
import gov.nist.javax.sip.parser.StringMsgParser;
import gov.nist.javax.sip.stack.MessageChannel;
import gov.nist.javax.sip.stack.SIPClientTransaction;
import gov.nist.javax.sip.stack.SIPServerTransaction;
import gov.nist.javax.sip.stack.SIPTransaction;
import gov.nist.javax.sip.stack.SIPTransactionStack;
import gov.nist.javax.sip.stack.ServerRequestInterface;
import gov.nist.javax.sip.stack.ServerResponseInterface;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.text.ParseException;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;

/**
 * SCTP message channel
 * 
 * @author Jeroen van Bemmel
 */
final class SCTPMessageChannel extends MessageChannel implements ParseExceptionListener {

	private final SCTPMessageProcessor processor;
	private InetSocketAddress peerAddress;			// destination address
	private InetSocketAddress peerSrcAddress;
	
	private final SctpChannel channel;
	private final SelectionKey key;
	
	private final MessageInfo messageInfo;	// outgoing SCTP options, cached

	private final ByteBuffer rxBuffer = ByteBuffer.allocateDirect( 5000 );
	
	private final StringMsgParser parser;	// Parser instance
	
	// for outgoing connections
	SCTPMessageChannel( SCTPMessageProcessor p, InetSocketAddress dest ) throws IOException {
		this.processor = p;
		this.peerAddress = dest;		
		this.peerSrcAddress = dest;		// assume the same, override upon packet
		
		this.messageInfo = MessageInfo.createOutgoing( dest, 0 );
		messageInfo.unordered( true );
		
		this.channel = SctpChannel.open( dest, 1, 1 );
		this.key = this.initChannel( p.getSelector() );
		
		parser = new StringMsgParser( this );
	}
	
	// For incoming connections
	SCTPMessageChannel( SCTPMessageProcessor p, SctpChannel c ) throws IOException {
		this.processor = p;
		
		SocketAddress a = c.getRemoteAddresses().iterator().next();
		this.messageInfo = MessageInfo.createOutgoing( a, 0 );
		messageInfo.unordered( true );
		
		this.channel = c;
		this.key = this.initChannel( p.getSelector() );
		
		parser = new StringMsgParser( this );
	}
	
	private SelectionKey initChannel( Selector s ) throws IOException {
		channel.configureBlocking( false );
		return channel.register( s, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this );
	}
	
	@Override
	public void close() {		
		try {
			this.key.cancel();
			channel.close();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			processor.removeChannel( this );
		}
	}

	void closeNoRemove() {		
		try {
			this.key.cancel();
			channel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public String getKey() {
		// Note: could put this in super class
		return getKey( this.getPeerInetAddress(), this.getPeerPort(), this.getTransport() );
	}

	@Override
	public String getPeerAddress() {
		return peerAddress.getHostString();
	}

	@Override
	protected InetAddress getPeerInetAddress() {
		return peerAddress.getAddress();
	}

	@Override
	public InetAddress getPeerPacketSourceAddress() {
		return peerSrcAddress.getAddress();
	}

	@Override
	public int getPeerPacketSourcePort() {
		return peerSrcAddress.getPort();
	}

	@Override
	public int getPeerPort() {
		return peerAddress.getPort();
	}

	@Override
	protected String getPeerProtocol() {
		return "sctp";	// else something really is weird ;)
	}

	@Override
	public SIPTransactionStack getSIPStack() {
		return processor.getSIPStack();
	}

	@Override
	public String getTransport() {
		return "sctp";
	}

	@Override
	public String getViaHost() {
		return processor.getSavedIpAddress();
	}

	@Override
	public int getViaPort() {
		return processor.getPort();
	}

	@Override
	public boolean isReliable() {
		return true;
	}

	@Override
	public boolean isSecure() {
		return false;
	}

	@Override
	public void sendMessage(SIPMessage sipMessage) throws IOException {
		byte[] msg = sipMessage.encodeAsBytes( this.getTransport() );
		this.sendMessage( msg, this.getPeerInetAddress(), this.getPeerPort(), false );
	}

	@Override
	protected void sendMessage(byte[] message, InetAddress receiverAddress,
			int receiverPort, boolean reconnectFlag) throws IOException {
		
		assert( receiverAddress.equals( peerAddress.getAddress() ) );
		assert( receiverPort == peerAddress.getPort() );
		
		// XX ignoring 'reconnect' for now
		channel.send( ByteBuffer.wrap(message), messageInfo );		
	}

	/**
	 * Called by SCTPMessageProcessor when one or more bytes are available for reading
	 * @throws IOException 
	 */
	void readMessages() throws IOException {
		long rxTime = System.currentTimeMillis();
		MessageInfo info = channel.receive( rxBuffer, null, null );
		// Assume it is 1 full message
		byte[] msg = new byte[ info.bytes() ];
		rxBuffer.flip();
		rxBuffer.get( msg );
		try {
			SIPMessage m = parser.parseSIPMessage( msg );
			this.processMessage( m, rxTime );
		} catch (ParseException e) {
			e.printStackTrace();
			this.close();
			throw new IOException( e.toString() );
		}		
	}
	
	/**
     * Actually proces the parsed message.
     * @param sipMessage
     * 
     * JvB: copied from UDPMessageChannel, TODO restructure
     */
    private void processMessage( SIPMessage sipMessage, long rxTime ) {
    	SIPTransactionStack sipStack = processor.getSIPStack();
        if (sipMessage instanceof SIPRequest) {
            SIPRequest sipRequest = (SIPRequest) sipMessage;

            // This is a request - process it.
            // So far so good -- we will commit this message if
            // all processing is OK.
            if (sipStack.getStackLogger().isLoggingEnabled(ServerLogger.TRACE_MESSAGES)) {
                sipStack.getServerLogger().logMessage(sipMessage, this
                        .getPeerHostPort().toString(), this.getHost() + ":"
                        + this.getPort(), false, rxTime);
            }
            ServerRequestInterface sipServerRequest = sipStack
                    .newSIPServerRequest(sipRequest, this);
            // Drop it if there is no request returned
            if (sipServerRequest == null) {
                if (sipStack.isLoggingEnabled()) {
                    sipStack.getStackLogger()
                            .logWarning("Null request interface returned -- dropping request");
                }


                return;
            }
            if (sipStack.isLoggingEnabled())
                sipStack.getStackLogger().logDebug("About to process "
                        + sipRequest.getFirstLine() + "/" + sipServerRequest);
            try {
                sipServerRequest.processRequest(sipRequest, this);
            } finally {
                if (sipServerRequest instanceof SIPTransaction) {
                    SIPServerTransaction sipServerTx = (SIPServerTransaction) sipServerRequest;
                    if (!sipServerTx.passToListener()) {
                        ((SIPTransaction) sipServerRequest).releaseSem();
                    }
                }
            }
            if (sipStack.isLoggingEnabled())
                sipStack.getStackLogger().logDebug("Done processing "
                        + sipRequest.getFirstLine() + "/" + sipServerRequest);

            // So far so good -- we will commit this message if
            // all processing is OK.

        } else {
            // Handle a SIP Reply message.
            SIPResponse sipResponse = (SIPResponse) sipMessage;
            try {
                sipResponse.checkHeaders();
            } catch (ParseException ex) {
                if (sipStack.isLoggingEnabled())
                    sipStack.getStackLogger()
                            .logError("Dropping Badly formatted response message >>> "
                                    + sipResponse);
                return;
            }
            ServerResponseInterface sipServerResponse = sipStack
                    .newSIPServerResponse(sipResponse, this);
            if (sipServerResponse != null) {
                try {
                    if (sipServerResponse instanceof SIPClientTransaction
                            && !((SIPClientTransaction) sipServerResponse)
                                    .checkFromTag(sipResponse)) {
                        if (sipStack.isLoggingEnabled())
                            sipStack.getStackLogger()
                                    .logError("Dropping response message with invalid tag >>> "
                                            + sipResponse);
                        return;
                    }

                    sipServerResponse.processResponse(sipResponse, this);
                } finally {
                    if (sipServerResponse instanceof SIPTransaction
                            && !((SIPTransaction) sipServerResponse)
                                    .passToListener())
                        ((SIPTransaction) sipServerResponse).releaseSem();
                }

                // Normal processing of message.
            } else {
                if (sipStack.isLoggingEnabled()) {
                    sipStack.getStackLogger().logDebug("null sipServerResponse!");
                }
            }

        }
    }
    
    /**
     * Implementation of the ParseExceptionListener interface.
     *
     * @param ex
     *            Exception that is given to us by the parser.
     * @throws ParseException
     *             If we choose to reject the header or message.
     *             
     * JvB: copied from UDPMessageChannel, TODO restructure!
     */
    public void handleException(ParseException ex, SIPMessage sipMessage,
            Class hdrClass, String header, String message)
            throws ParseException {
        if (getSIPStack().isLoggingEnabled())
            this.getSIPStack().getStackLogger().logException(ex);
        // Log the bad message for later reference.
        if ((hdrClass != null)
                && (hdrClass.equals(From.class) || hdrClass.equals(To.class)
                        || hdrClass.equals(CSeq.class)
                        || hdrClass.equals(Via.class)
                        || hdrClass.equals(CallID.class)
                        || hdrClass.equals(RequestLine.class) || hdrClass
                        .equals(StatusLine.class))) {
        	getSIPStack().getStackLogger().logError("BAD MESSAGE!");
        	getSIPStack().getStackLogger().logError(message);
            throw ex;
        } else {
            sipMessage.addUnparsed(header);
        }
    }
	
}