package com.topodroid.TDX;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * A local HTTP server that plays the survey-upload routes of a SilexGIS server for the JVM tests.
 * Answers are queued per "METHOD path": each request takes the next one, the last one repeats.
 * Every request is recorded. All answers are invented.
 */
class FakeSilexGis
{
  /** a recorded request */
  static class Request
  {
    final String method;
    final String path;    // path only
    final String query;   // raw query, null if none
    final Map< String, String > headers = new HashMap<>(); // lowercase names
    final byte[] body;

    Request( String m, String p, String q, byte[] b ) { method = m; path = p; query = q; body = b; }

    String text() { return new String( body, StandardCharsets.UTF_8 ); }
    String key() { return method + " " + path; }
  }

  /** an answer: status, content type, body; DROP closes the connection without an answer */
  static class Answer
  {
    static final int DROP = -1;
    final int status;
    final String type;
    final byte[] body;

    Answer( int s, String t, byte[] b ) { status = s; type = t; body = b; }
  }

  private final HttpServer mServer;
  private final Map< String, LinkedList< Answer > > mAnswers = new HashMap<>();
  final List< Request > requests = Collections.synchronizedList( new ArrayList< Request >() );

  FakeSilexGis() throws IOException
  {
    mServer = HttpServer.create( new InetSocketAddress( InetAddress.getLoopbackAddress(), 0 ), 0 );
    mServer.createContext( "/", this::answer );
    mServer.start();
  }

  String base() { return "http://127.0.0.1:" + mServer.getAddress().getPort(); }

  void stop() { mServer.stop( 0 ); }

  synchronized FakeSilexGis on( String method, String path, int status, String json )
  {
    String type = ( status >= 400 )? "application/problem+json" : "application/json";
    return on( method, path, new Answer( status, type, json.getBytes( StandardCharsets.UTF_8 ) ) );
  }

  synchronized FakeSilexGis on( String method, String path, Answer answer )
  {
    mAnswers.computeIfAbsent( method + " " + path, k -> new LinkedList<>() ).add( answer );
    return this;
  }

  synchronized FakeSilexGis drop( String method, String path ) { return on( method, path, new Answer( Answer.DROP, null, new byte[0] ) ); }

  private synchronized Answer next( String key )
  {
    LinkedList< Answer > list = mAnswers.get( key );
    if ( list == null || list.isEmpty() ) return null;
    return ( list.size() > 1 )? list.removeFirst() : list.getFirst();
  }

  /** @return the recorded requests of a route */
  List< Request > of( String method, String path )
  {
    List< Request > ret = new ArrayList<>();
    synchronized ( requests ) {
      for ( Request r : requests ) if ( r.method.equals( method ) && r.path.equals( path ) ) ret.add( r );
    }
    return ret;
  }

  /** @return the methods and paths of the recorded requests, in order */
  List< String > trail()
  {
    List< String > ret = new ArrayList<>();
    synchronized ( requests ) {
      for ( Request r : requests ) ret.add( r.key() );
    }
    return ret;
  }

  private void answer( HttpExchange ex ) throws IOException
  {
    byte[] in = readAll( ex.getRequestBody() );
    Request req = new Request( ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), in );
    for ( Map.Entry< String, List< String > > h : ex.getRequestHeaders().entrySet() ) {
      if ( h.getKey() != null && ! h.getValue().isEmpty() ) req.headers.put( h.getKey().toLowerCase(), h.getValue().get( 0 ) );
    }
    requests.add( req );
    Answer a = next( req.key() );
    if ( a == null ) {
      a = new Answer( 404, "application/problem+json",
        "{\"title\":\"Not Found\",\"status\":404,\"code\":\"route.not_found\"}".getBytes( StandardCharsets.UTF_8 ) );
    }
    if ( a.status == Answer.DROP ) { // the connection goes away without an answer
      ex.close();
      return;
    }
    if ( a.type != null ) ex.getResponseHeaders().add( "Content-Type", a.type );
    ex.sendResponseHeaders( a.status, a.body.length == 0 ? -1 : a.body.length );
    if ( a.body.length > 0 ) {
      try ( OutputStream os = ex.getResponseBody() ) { os.write( a.body ); }
    }
    ex.close();
  }

  static byte[] readAll( InputStream is ) throws IOException
  {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ( ( n = is.read( buf ) ) > 0 ) bos.write( buf, 0, n );
    return bos.toByteArray();
  }
}
