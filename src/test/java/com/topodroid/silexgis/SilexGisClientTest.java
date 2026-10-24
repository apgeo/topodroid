package com.topodroid.silexgis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.topodroid.TDX.SilexGisClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * SilexGisClient against a local HTTP server that answers like the SilexGIS routes the
 * settings dialog calls. All answers are invented.
 */
public class SilexGisClientTest
{
  private static final String KEY = "sgx_TestKeyAAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIII";

  private HttpServer server;
  private String base;
  /** path -> { status, content type, body } */
  private final Map<String, String[]> answers = new ConcurrentHashMap<>();
  private final Map<String, String> seenAuthorization = new ConcurrentHashMap<>();
  private final Map<String, String> seenContract = new ConcurrentHashMap<>();

  @Before
  public void start() throws IOException
  {
    server = HttpServer.create( new InetSocketAddress( InetAddress.getLoopbackAddress(), 0 ), 0 );
    server.createContext( "/", this::answer );
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @After
  public void stop() { server.stop( 0 ); }

  private void answer( HttpExchange ex ) throws IOException
  {
    String path = ex.getRequestURI().getPath();
    String auth = ex.getRequestHeaders().getFirst( "Authorization" );
    if ( auth != null ) seenAuthorization.put( path, auth );
    String contract = ex.getRequestHeaders().getFirst( "X-SilexGis-Contract" );
    if ( contract != null ) seenContract.put( path, contract );
    String[] a = answers.get( path );
    if ( a == null ) a = new String[] { "404", "application/problem+json",
        "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"code\":\"route.not_found\"}" };
    byte[] body = a[2].getBytes( StandardCharsets.UTF_8 );
    ex.getResponseHeaders().add( "Content-Type", a[1] );
    ex.sendResponseHeaders( Integer.parseInt( a[0] ), body.length == 0 ? -1 : body.length );
    if ( body.length > 0 ) {
      try ( OutputStream os = ex.getResponseBody() ) { os.write( body ); }
    }
    ex.close();
  }

  private void json( String path, int status, String body )
  {
    answers.put( path, new String[] { Integer.toString( status ), "application/json", body } );
  }

  private void problem( String path, int status, String body )
  {
    answers.put( path, new String[] { Integer.toString( status ), "application/problem+json", body } );
  }

  private SilexGisClient client() { return new SilexGisClient( base + "/", KEY ); }

  // ---------------------------------------------------------------------------- success paths

  @Test
  public void meReturnsTheAccountAndSendsTheKeyAsBearer()
  {
    json( "/api/v1/me", 200,
        "{\"id\":\"0190a000-0000-7000-8000-000000000001\",\"userName\":\"ion.a\",\"email\":\"ion.a@example.org\","
      + "\"emailConfirmed\":true,\"displayName\":\"Ion A.\"}" );
    SilexGisClient.Result r = client().me();
    assertTrue( r.isOk() );
    assertEquals( 200, r.status );
    assertEquals( "ion.a", r.getString( "userName", null ) );
    assertEquals( "Ion A. <ion.a@example.org>", SilexGisClient.accountLabel( r ) );
    assertEquals( "Bearer " + KEY, seenAuthorization.get( "/api/v1/me" ) );
    assertEquals( "1", seenContract.get( "/api/v1/me" ) );
  }

  @Test
  public void accountLabelFallsBackToTheUserNameWhenThereIsNoDisplayName()
  {
    json( "/api/v1/me", 200, "{\"userName\":\"ion.a\",\"email\":\"ion.a@example.org\",\"displayName\":null}" );
    assertEquals( "ion.a <ion.a@example.org>", SilexGisClient.accountLabel( client().me() ) );
  }

  @Test
  public void aboutReturnsNameAndVersion()
  {
    json( "/api/v1/about", 200,
        "{\"name\":\"SilexGIS\",\"version\":\"0.1.0-test\",\"license\":\"AGPL-3.0-or-later\",\"sourceUrl\":\"https://example.org/src\"}" );
    SilexGisClient.Result r = client().about();
    assertTrue( r.isOk() );
    assertEquals( "SilexGIS", r.getString( "name", null ) );
    assertEquals( "0.1.0-test", r.getString( "version", null ) );
  }

  @Test
  public void capabilitiesTellWhetherTheServerCompiles()
  {
    json( "/api/v1/survey-uploads/capabilities", 200,
        "{\"contractVersion\":1,\"sources\":[\"topodroid\"],\"maxFileBytes\":262144000,"
      + "\"compiler\":{\"available\":true,\"version\":\"therion 6.3.4\",\"outputs\":[\"lox\",\"3d\"]},"
      + "\"pollIntervalSeconds\":3}" );
    SilexGisClient.Result r = client().capabilities();
    assertTrue( r.isOk() );
    assertTrue( SilexGisClient.compilerAvailable( r ) );
    assertEquals( "Bearer " + KEY, seenAuthorization.get( "/api/v1/survey-uploads/capabilities" ) );

    json( "/api/v1/survey-uploads/capabilities", 200, "{\"contractVersion\":1,\"compiler\":{\"available\":false}}" );
    assertFalse( SilexGisClient.compilerAvailable( client().capabilities() ) );
  }

  // ---------------------------------------------------------------------------- refusals

  @Test
  public void status401IsKeyRefused()
  {
    problem( "/api/v1/me", 401, "" );
    SilexGisClient.Result r = client().me();
    assertEquals( SilexGisClient.KEY_REFUSED, r.kind );
    assertEquals( 401, r.status );
    assertFalse( r.isOk() );
  }

  @Test
  public void status403AccessKeyNotAcceptedIsDistinct()
  {
    problem( "/api/v1/about", 403,
        "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\"auth.access_key_not_accepted\","
      + "\"detail\":\"This route does not accept an access key.\"}" );
    SilexGisClient.Result r = client().about();
    assertEquals( SilexGisClient.KEY_NOT_ACCEPTED, r.kind );
    assertEquals( "auth.access_key_not_accepted", r.code );
  }

  @Test
  public void otherStatus403IsAProblem()
  {
    problem( "/api/v1/about", 403, "{\"title\":\"Forbidden\",\"status\":403,\"code\":\"permission.denied\"}" );
    SilexGisClient.Result r = client().about();
    assertEquals( SilexGisClient.PROBLEM, r.kind );
    assertEquals( "permission.denied", r.code );
  }

  @Test
  public void problemDetails400IsParsed()
  {
    problem( "/api/v1/survey-uploads/capabilities", 400,
        "{\"type\":\"https://example.org/problems/x\",\"title\":\"Invalid request\",\"status\":400,"
      + "\"code\":\"survey_upload.source_unsupported\",\"detail\":\"Source 'x' is not supported.\"}" );
    SilexGisClient.Result r = client().capabilities();
    assertEquals( SilexGisClient.PROBLEM, r.kind );
    assertEquals( 400, r.status );
    assertEquals( "survey_upload.source_unsupported", r.code );
    assertEquals( "Invalid request", r.title );
    assertEquals( "Source 'x' is not supported.", r.detail );
    assertEquals( "Source 'x' is not supported.", r.message() );
  }

  @Test
  public void errorWithoutABodyIsAProblemWithNoCode()
  {
    problem( "/api/v1/me", 500, "" );
    SilexGisClient.Result r = client().me();
    assertEquals( SilexGisClient.PROBLEM, r.kind );
    assertNull( r.code );
    assertEquals( "HTTP 500", r.message() );
  }

  @Test
  public void malformedJsonIsBadResponse()
  {
    json( "/api/v1/me", 200, "{\"userName\":\"ion.a\"," );
    assertEquals( SilexGisClient.BAD_RESPONSE, client().me().kind );
    answers.put( "/api/v1/about", new String[] { "200", "text/html", "<html>proxy login</html>" } );
    assertEquals( SilexGisClient.BAD_RESPONSE, client().about().kind );
  }

  @Test
  public void unreachableServerIsNetwork() throws IOException
  {
    int port;
    try ( ServerSocket s = new ServerSocket( 0, 1, InetAddress.getLoopbackAddress() ) ) { port = s.getLocalPort(); }
    SilexGisClient.Result r = new SilexGisClient( "http://127.0.0.1:" + port, KEY ).me();
    assertEquals( SilexGisClient.NETWORK, r.kind );
    assertEquals( 0, r.status );
  }

  // ---------------------------------------------------------------------------- URL handling

  @Test
  public void cleartextToANonLocalHostIsRefusedWithoutConnecting()
  {
    SilexGisClient c = new SilexGisClient( "http://silexgis.example.org", KEY );
    SilexGisClient.Result r = c.me();
    assertEquals( SilexGisClient.BAD_URL, r.kind );
    assertEquals( SilexGisClient.URL_CLEARTEXT, r.url );
    assertEquals( 0, r.status );
  }

  @Test
  public void baseUrlChecks()
  {
    assertEquals( SilexGisClient.URL_OK,        SilexGisClient.checkBaseUrl( "https://silexgis.example.org" ) );
    assertEquals( SilexGisClient.URL_OK,        SilexGisClient.checkBaseUrl( "http://10.0.2.2:5080" ) );
    assertEquals( SilexGisClient.URL_OK,        SilexGisClient.checkBaseUrl( "http://127.0.0.1:5080/" ) );
    assertEquals( SilexGisClient.URL_OK,        SilexGisClient.checkBaseUrl( "http://LOCALHOST:5080" ) );
    assertEquals( SilexGisClient.URL_CLEARTEXT, SilexGisClient.checkBaseUrl( "http://192.168.1.10" ) );
    assertEquals( SilexGisClient.URL_CLEARTEXT, SilexGisClient.checkBaseUrl( "http://10.0.2.20" ) );
    assertEquals( SilexGisClient.URL_INVALID,   SilexGisClient.checkBaseUrl( "ftp://silexgis.example.org" ) );
    assertEquals( SilexGisClient.URL_INVALID,   SilexGisClient.checkBaseUrl( "silexgis.example.org" ) );
    assertEquals( SilexGisClient.URL_EMPTY,     SilexGisClient.checkBaseUrl( "  " ) );
  }

  @Test
  public void trailingSlashesAreStripped()
  {
    assertEquals( "https://silexgis.example.org", SilexGisClient.normalizeBaseUrl( " https://silexgis.example.org// " ) );
    assertEquals( "https://silexgis.example.org/sub", new SilexGisClient( "https://silexgis.example.org/sub/", KEY ).getBaseUrl() );
  }

  @Test
  public void missingKeyIsNotConfigured()
  {
    SilexGisClient c = new SilexGisClient( base, "  " );
    assertFalse( c.isConfigured() );
    assertEquals( SilexGisClient.NOT_CONFIGURED, c.me().kind );
    assertTrue( seenAuthorization.isEmpty() );
  }

  @Test
  public void keyIsMaskedToEightCharacters()
  {
    assertEquals( "sgx_Test…", SilexGisClient.maskKey( KEY ) );
    assertEquals( "", SilexGisClient.maskKey( null ) );
    assertEquals( "sgx_", SilexGisClient.maskKey( "sgx_" ) );
  }
}
