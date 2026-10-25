package com.topodroid.silexgis;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.topodroid.TDX.SilexGisClient;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The list parser and the download helper of SilexGisClient, used by the import from SilexGIS,
 * against local HTTP servers. All names, surveys and bytes are invented.
 */
public class SilexGisImportClientTest
{
  private static final String KEY = "sgx_TestKeyAAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIII";

  private HttpServer server;
  private HttpServer other; // a second origin: another port
  private String base;
  private String otherBase;
  private File dir;

  private final Map<String, byte[]> files = new ConcurrentHashMap<>();       // path -> body of a 200
  private final Map<String, String[]> problems = new ConcurrentHashMap<>();  // path -> { status, body }
  private final Map<String, String> redirects = new ConcurrentHashMap<>();
  private final Map<String, String> seenAuthorization = new ConcurrentHashMap<>();
  private final Map<String, String> seenContract = new ConcurrentHashMap<>();
  private final Map<String, String> seenQuery = new ConcurrentHashMap<>();
  private final Map<String, String> otherSeenAuthorization = new ConcurrentHashMap<>();
  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicInteger otherHits = new AtomicInteger();
  private volatile int shortBody = -1; // if set, the server announces the body but sends only this many bytes

  @Before
  public void start() throws IOException
  {
    server = HttpServer.create( new InetSocketAddress( InetAddress.getLoopbackAddress(), 0 ), 0 );
    server.createContext( "/", this::answer );
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    other = HttpServer.create( new InetSocketAddress( InetAddress.getLoopbackAddress(), 0 ), 0 );
    other.createContext( "/", this::answerOther );
    other.start();
    otherBase = "http://127.0.0.1:" + other.getAddress().getPort();
    dir = Files.createTempDirectory( "silexgis-import-test" ).toFile();
  }

  @After
  public void stop()
  {
    server.stop( 0 );
    other.stop( 0 );
    File[] list = dir.listFiles();
    if ( list != null ) for ( File f : list ) f.delete();
    dir.delete();
  }

  private void answer( HttpExchange ex ) throws IOException
  {
    hits.incrementAndGet();
    String path = ex.getRequestURI().getPath();
    String auth = ex.getRequestHeaders().getFirst( "Authorization" );
    if ( auth != null ) seenAuthorization.put( path, auth );
    String contract = ex.getRequestHeaders().getFirst( "X-SilexGis-Contract" );
    if ( contract != null ) seenContract.put( path, contract );
    String query = ex.getRequestURI().getRawQuery();
    if ( query != null ) seenQuery.put( path, query );
    String[] p = problems.get( path );
    String redirect = redirects.get( path );
    byte[] body = files.get( path );
    if ( redirect != null ) {
      ex.getResponseHeaders().add( "Location", redirect );
      ex.sendResponseHeaders( 302, -1 );
      ex.close();
    } else if ( p != null ) {
      byte[] b = p[1].getBytes( StandardCharsets.UTF_8 );
      ex.getResponseHeaders().add( "Content-Type", "application/problem+json" );
      ex.sendResponseHeaders( Integer.parseInt( p[0] ), b.length );
      try ( OutputStream os = ex.getResponseBody() ) { os.write( b ); }
    } else if ( body != null ) {
      ex.getResponseHeaders().add( "Content-Type", "application/octet-stream" );
      ex.sendResponseHeaders( 200, body.length );
      try ( OutputStream os = ex.getResponseBody() ) {
        if ( shortBody >= 0 ) {
          os.write( body, 0, shortBody );
        } else {
          os.write( body );
        }
      } catch ( IOException e ) {
        // the handler closes a body shorter than announced on purpose
      }
    } else {
      byte[] b = "{\"title\":\"Not Found\",\"code\":\"survey_upload.not_found\"}".getBytes( StandardCharsets.UTF_8 );
      ex.getResponseHeaders().add( "Content-Type", "application/problem+json" );
      ex.sendResponseHeaders( 404, b.length );
      try ( OutputStream os = ex.getResponseBody() ) { os.write( b ); }
    }
    ex.close();
  }

  private void answerOther( HttpExchange ex ) throws IOException
  {
    otherHits.incrementAndGet();
    String auth = ex.getRequestHeaders().getFirst( "Authorization" );
    if ( auth != null ) otherSeenAuthorization.put( ex.getRequestURI().getPath(), auth );
    byte[] body = files.get( "other:" + ex.getRequestURI().getPath() );
    if ( body == null ) {
      ex.sendResponseHeaders( 404, -1 );
    } else {
      ex.sendResponseHeaders( 200, body.length );
      try ( OutputStream os = ex.getResponseBody() ) { os.write( body ); }
    }
    ex.close();
  }

  private SilexGisClient client() { return new SilexGisClient( base, KEY ); }

  private static byte[] bytes( int n, int seed )
  {
    byte[] b = new byte[ n ];
    for ( int k = 0; k < n; ++k ) b[k] = (byte)( ( k * 31 + seed ) & 0xff );
    return b;
  }

  private static final String LIST =
      "{\"total\":3,\"items\":["
    + "{\"id\":\"u1\",\"caveId\":\"c1\",\"caveName\":\"Pestera Inventata\",\"surveyName\":\"inventata-1\",\"surveyTitle\":\"Galeria noua\","
    +  "\"surveyDate\":\"2026-10-20\",\"uploadedBy\":{\"displayName\":\"Ion A.\"},\"createdAt\":\"2026-10-25T01:32:00Z\",\"status\":\"succeeded\","
    +  "\"archive\":{\"name\":\"inventata-1-20261025T013200Z.zip\",\"sizeBytes\":48213,\"contentUrl\":\"/files/a1.zip\"},"
    +  "\"therionSources\":[{\"name\":\"inventata-1-20261025T013200Z.th\",\"contentUrl\":\"/files/a1.th\"}]},"
    + "{\"id\":\"u2\",\"caveName\":\"Avenul Inventat\",\"surveyName\":\"avenul-2\",\"createdAt\":\"2026-10-24T10:00:00Z\","
    +  "\"uploadedBy\":{\"displayName\":\"Maria B.\"},\"archive\":{\"name\":\"avenul-2.zip\",\"sizeBytes\":1000,\"contentUrl\":\"/files/a2.zip\"},\"therionSources\":[]},"
    + "{\"id\":\"u3\",\"caveName\":\"Pestera Inventata\",\"surveyName\":\"inventata-1\",\"createdAt\":\"2026-10-26T09:00:00Z\","
    +  "\"archive\":{\"name\":\"inventata-1-b.zip\",\"contentUrl\":\"/files/a3.zip\"}}"
    + "]}";

  // ---------------------------------------------------------------------------- the list

  @Test
  public void listUploadsAsksTheTopodroidSourceAndTheSizeOfThePage()
  {
    files.put( "/api/v1/survey-uploads", LIST.getBytes( StandardCharsets.UTF_8 ) );
    SilexGisClient.Result r = client().listUploads( "topodroid", 100 );
    assertTrue( r.isOk() );
    assertEquals( "source=topodroid&pageSize=100", seenQuery.get( "/api/v1/survey-uploads" ) );
    assertEquals( "Bearer " + KEY, seenAuthorization.get( "/api/v1/survey-uploads" ) );
    assertEquals( "1", seenContract.get( "/api/v1/survey-uploads" ) );
    ArrayList<SilexGisClient.Upload> ups = SilexGisClient.parseUploads( r.json );
    assertNotNull( ups );
    assertEquals( 3, ups.size() );
  }

  @Test
  public void parserReadsTheFieldsOfAnItem() throws Exception
  {
    ArrayList<SilexGisClient.Upload> ups = SilexGisClient.parseUploads( new JSONObject( LIST ) );
    SilexGisClient.Upload u = ups.get( 0 );
    assertEquals( "u1", u.id );
    assertEquals( "Pestera Inventata", u.caveName );
    assertEquals( "Galeria noua", u.title );
    assertEquals( "inventata-1", u.surveyName );
    assertEquals( "Ion A.", u.uploader );
    assertEquals( "2026-10-25T01:32:00Z", u.createdAt );
    assertEquals( 48213L, u.size );
    assertEquals( "inventata-1-20261025T013200Z.zip", u.archiveName );
    assertEquals( "/files/a1.zip", u.archiveUrl );
    assertEquals( 1, u.therion.size() );
    assertEquals( "inventata-1-20261025T013200Z.th", u.therion.get( 0 )[0] );
    assertEquals( "/files/a1.th", u.therion.get( 0 )[1] );
  }

  @Test
  public void parserFallsBackToTheSurveyNameAndToUnknownValues() throws Exception
  {
    ArrayList<SilexGisClient.Upload> ups = SilexGisClient.parseUploads( new JSONObject( LIST ) );
    assertEquals( "avenul-2", ups.get( 1 ).title );   // no surveyTitle
    assertTrue( ups.get( 1 ).therion.isEmpty() );
    assertEquals( "", ups.get( 2 ).uploader );        // no uploadedBy
    assertEquals( -1L, ups.get( 2 ).size );           // no sizeBytes
  }

  @Test
  public void parserSkipsItemsWithoutAnArchiveAddress() throws Exception
  {
    String text = "{\"items\":[{\"id\":\"x\",\"caveName\":\"A\"},{\"id\":\"y\",\"archive\":{\"name\":\"y.zip\"}},"
                + "{\"id\":\"z\",\"archive\":{\"name\":\"z.zip\",\"contentUrl\":\"\"}},"
                + "{\"id\":\"w\",\"archive\":{\"name\":\"w.zip\",\"contentUrl\":\"/f/w.zip\"}}]}";
    ArrayList<SilexGisClient.Upload> ups = SilexGisClient.parseUploads( new JSONObject( text ) );
    assertEquals( 1, ups.size() );
    assertEquals( "w", ups.get( 0 ).id );
  }

  @Test
  public void parserTakesNullMembersAsMissing() throws Exception
  {
    String text = "{\"items\":[{\"id\":\"w\",\"caveName\":null,\"surveyName\":null,\"uploadedBy\":null,\"therionSources\":null,"
                + "\"archive\":{\"name\":null,\"sizeBytes\":null,\"contentUrl\":\"/f/w.zip\"}}]}";
    SilexGisClient.Upload u = SilexGisClient.parseUploads( new JSONObject( text ) ).get( 0 );
    assertEquals( "", u.caveName );
    assertEquals( "", u.title );
    assertEquals( "", u.archiveName );
    assertEquals( "", u.uploader );
    assertTrue( u.therion.isEmpty() );
  }

  @Test
  public void parserReturnsNullWithoutAListOfItems() throws Exception
  {
    assertNull( SilexGisClient.parseUploads( null ) );
    assertNull( SilexGisClient.parseUploads( new JSONObject( "{\"total\":0}" ) ) );
    assertEquals( 0, SilexGisClient.parseUploads( new JSONObject( "{\"items\":[]}" ) ).size() );
  }

  @Test
  public void uploadsAreSortedByCaveThenTitleThenNewestFirst() throws Exception
  {
    ArrayList<SilexGisClient.Upload> ups = SilexGisClient.parseUploads( new JSONObject( LIST ) );
    SilexGisClient.sortUploads( ups );
    assertEquals( "Avenul Inventat", ups.get( 0 ).caveName );
    assertEquals( "Pestera Inventata", ups.get( 1 ).caveName );
    assertEquals( "Pestera Inventata", ups.get( 2 ).caveName );
    // u3 has the title "inventata-1" (surveyName) and is newer than u1 "Galeria noua": titles come first
    assertEquals( "u1", ups.get( 1 ).id );
    assertEquals( "u3", ups.get( 2 ).id );
  }

  @Test
  public void versionsOfASurveyAreNewestFirst() throws Exception
  {
    String text = "{\"items\":["
      + "{\"id\":\"old\",\"caveName\":\"C\",\"surveyName\":\"s\",\"createdAt\":\"2026-10-01T00:00:00Z\",\"archive\":{\"contentUrl\":\"/a\"}},"
      + "{\"id\":\"new\",\"caveName\":\"C\",\"surveyName\":\"s\",\"createdAt\":\"2026-10-09T00:00:00Z\",\"archive\":{\"contentUrl\":\"/b\"}},"
      + "{\"id\":\"mid\",\"caveName\":\"C\",\"surveyName\":\"s\",\"createdAt\":\"2026-10-05T00:00:00Z\",\"archive\":{\"contentUrl\":\"/c\"}}]}";
    ArrayList<SilexGisClient.Upload> ups = SilexGisClient.parseUploads( new JSONObject( text ) );
    SilexGisClient.sortUploads( ups );
    assertEquals( "new", ups.get( 0 ).id );
    assertEquals( "mid", ups.get( 1 ).id );
    assertEquals( "old", ups.get( 2 ).id );
  }

  @Test
  public void filterMatchesCaveTitleNameAndUploaderIgnoringCase() throws Exception
  {
    ArrayList<SilexGisClient.Upload> ups = SilexGisClient.parseUploads( new JSONObject( LIST ) );
    assertEquals( 3, SilexGisClient.filterUploads( ups, "" ).size() );
    assertEquals( 3, SilexGisClient.filterUploads( ups, null ).size() );
    assertEquals( 2, SilexGisClient.filterUploads( ups, "PESTERA" ).size() );
    assertEquals( 1, SilexGisClient.filterUploads( ups, "galeria" ).size() );
    assertEquals( 1, SilexGisClient.filterUploads( ups, "avenul-2" ).size() );
    assertEquals( 1, SilexGisClient.filterUploads( ups, " maria " ).size() );
    assertEquals( 0, SilexGisClient.filterUploads( ups, "nothing like it" ).size() );
  }

  // ---------------------------------------------------------------------------- file names

  @Test
  public void safeFileNameKeepsTheFileInItsFolder()
  {
    assertEquals( "passwd.zip", SilexGisClient.safeFileName( "../../etc/passwd", ".zip" ) );
    assertEquals( "x.zip", SilexGisClient.safeFileName( "..\\..\\x.zip", ".zip" ) );
    assertEquals( "evil.ZIP", SilexGisClient.safeFileName( "/abs/evil.ZIP", ".zip" ) ); // the extension is matched ignoring case
    assertEquals( "survey.zip", SilexGisClient.safeFileName( "", ".zip" ) );
    assertEquals( "survey.zip", SilexGisClient.safeFileName( null, ".zip" ) );
    assertEquals( "survey.zip", SilexGisClient.safeFileName( "...", ".zip" ) );
    assertEquals( "hidden.zip", SilexGisClient.safeFileName( ".hidden", ".zip" ) );
  }

  @Test
  public void safeFileNameReplacesWhatIsNotPlain()
  {
    assertEquals( "Pestera_Inventata-1_2026.zip", SilexGisClient.safeFileName( "Pestera Inventata-1:2026", ".zip" ) );
    assertEquals( "a_b_c.th", SilexGisClient.safeFileName( "a b?c.th", ".th" ) );
    assertEquals( "name.th", SilexGisClient.safeFileName( "name", ".th" ) );
    assertEquals( "P__estera.zip", SilexGisClient.safeFileName( "P\u0102\u00ceestera", ".zip" ) ); // each other character becomes one underscore
  }

  @Test
  public void newFileDoesNotReplaceAnExistingFile() throws Exception
  {
    assertEquals( "s.zip", SilexGisClient.newFile( dir, "s.zip" ).getName() );
    assertTrue( new File( dir, "s.zip" ).createNewFile() );
    assertEquals( "s-1.zip", SilexGisClient.newFile( dir, "s.zip" ).getName() );
    assertTrue( new File( dir, "s-1.zip" ).createNewFile() );
    assertEquals( "s-2.zip", SilexGisClient.newFile( dir, "s.zip" ).getName() );
  }

  // ---------------------------------------------------------------------------- texts

  @Test
  public void sizeIsInKilobytesUnderAMegabyteAndInMegabytesAbove()
  {
    assertEquals( "", SilexGisClient.sizeText( -1L, -1L ) );
    assertEquals( "0.0 kB", SilexGisClient.sizeText( 0L, -1L ) );
    assertEquals( "2.2 kB", SilexGisClient.sizeText( 2276L, -1L ) );
    assertEquals( "1024.0 kB", SilexGisClient.sizeText( 1048575L, -1L ) ); // 1023.999 rounds up
    assertEquals( "1.0 MB", SilexGisClient.sizeText( 1048576L, -1L ) );
    assertEquals( "12.5 MB", SilexGisClient.sizeText( 13107200L, -1L ) );
    assertEquals( "1.0 / 12.5 MB", SilexGisClient.sizeText( 1048576L, 13107200L ) );
    assertEquals( "0.5 / 2.2 kB", SilexGisClient.sizeText( 512L, 2276L ) );
  }

  @Test
  public void timeIsShownToTheMinute()
  {
    assertEquals( "2026-10-25 01:32", SilexGisClient.shortTime( "2026-10-25T01:32:00Z" ) );
    assertEquals( "2026-10-25 01:32", SilexGisClient.shortTime( "2026-10-25T01:32:00.123456+00:00" ) );
    assertEquals( "2026-10-25", SilexGisClient.shortTime( "2026-10-25" ) );
    assertEquals( "", SilexGisClient.shortTime( "" ) );
  }

  // ---------------------------------------------------------------------------- download

  @Test
  public void downloadWritesTheFileFromARelativeAddressAndReportsTheProgress() throws Exception
  {
    byte[] body = bytes( 300000, 7 ); // more than four buffers
    files.put( "/files/a1.zip", body );
    File dest = new File( dir, "a1.zip" );
    final List<long[]> seen = new ArrayList<>();
    SilexGisClient.Result r = client().download( "/files/a1.zip", dest, new SilexGisClient.Progress() {
      @Override public boolean onProgress( long done, long total ) { seen.add( new long[] { done, total } ); return true; }
    } );
    assertTrue( r.message(), r.isOk() );
    assertArrayEquals( body, Files.readAllBytes( dest.toPath() ) );
    assertFalse( new File( dir, "a1.zip.part" ).exists() );
    assertTrue( seen.size() >= 4 );
    long last = 0;
    for ( long[] s : seen ) {
      assertEquals( 300000L, s[1] );
      assertTrue( s[0] > last );
      last = s[0];
    }
    assertEquals( 300000L, last );
    assertEquals( "Bearer " + KEY, seenAuthorization.get( "/files/a1.zip" ) );
    assertEquals( "1", seenContract.get( "/files/a1.zip" ) );
  }

  @Test
  public void downloadWorksWithoutAProgressListenerAndOfAnEmptyBody() throws Exception
  {
    files.put( "/files/e.th", new byte[ 0 ] );
    File dest = new File( dir, "e.th" );
    SilexGisClient.Result r = client().download( "/files/e.th", dest, null );
    assertTrue( r.message(), r.isOk() );
    assertEquals( 0L, dest.length() );
  }

  @Test
  public void downloadReplacesAFileOfTheSameName() throws Exception
  {
    files.put( "/files/b.zip", bytes( 100, 1 ) );
    File dest = new File( dir, "b.zip" );
    Files.write( dest.toPath(), bytes( 5000, 9 ) );
    assertTrue( client().download( "/files/b.zip", dest, null ).isOk() );
    assertArrayEquals( bytes( 100, 1 ), Files.readAllBytes( dest.toPath() ) );
  }

  @Test
  public void downloadSendsTheKeyOnlyToTheServerOfTheBaseUrl() throws Exception
  {
    files.put( "other:/signed/x.zip", bytes( 1000, 3 ) );
    File dest = new File( dir, "x.zip" );
    SilexGisClient.Result r = client().download( otherBase + "/signed/x.zip?sig=abc", dest, null );
    assertTrue( r.message(), r.isOk() );
    assertArrayEquals( bytes( 1000, 3 ), Files.readAllBytes( dest.toPath() ) );
    assertEquals( 1, otherHits.get() );
    assertEquals( 0, hits.get() );
    assertNull( otherSeenAuthorization.get( "/signed/x.zip" ) );
  }

  @Test
  public void downloadSendsTheKeyWithAFullAddressOfTheSameServer() throws Exception
  {
    files.put( "/files/c.zip", bytes( 10, 3 ) );
    File dest = new File( dir, "c.zip" );
    assertTrue( client().download( base + "/files/c.zip", dest, null ).isOk() );
    assertEquals( "Bearer " + KEY, seenAuthorization.get( "/files/c.zip" ) );
  }

  @Test
  public void downloadRefusesAnAddressThatIsNotHttp() throws Exception
  {
    File dest = new File( dir, "n.zip" );
    for ( String bad : new String[] { "ftp://127.0.0.1/x.zip", "files/x.zip", "", "file:///etc/passwd", null } ) {
      SilexGisClient.Result r = client().download( bad, dest, null );
      assertEquals( bad, SilexGisClient.BAD_URL, r.kind );
      assertEquals( bad, SilexGisClient.URL_INVALID, r.url );
    }
    assertFalse( dest.exists() );
    assertEquals( 0, hits.get() );
  }

  @Test
  public void downloadRefusesCleartextToAHostThatIsNotLocal() throws Exception
  {
    File dest = new File( dir, "n.zip" );
    SilexGisClient.Result r = client().download( "http://files.example.org/x.zip", dest, null );
    assertEquals( SilexGisClient.BAD_URL, r.kind );
    assertEquals( SilexGisClient.URL_CLEARTEXT, r.url );
    assertFalse( dest.exists() );
  }

  @Test
  public void downloadNeedsTheServerAndTheKey() throws Exception
  {
    File dest = new File( dir, "n.zip" );
    assertEquals( SilexGisClient.NOT_CONFIGURED, new SilexGisClient( "", KEY ).download( "/x", dest, null ).kind );
    assertEquals( SilexGisClient.NOT_CONFIGURED, new SilexGisClient( base, "" ).download( "/x", dest, null ).kind );
    assertEquals( SilexGisClient.BAD_KEY, new SilexGisClient( base, "sgx_bad key" ).download( "/x", dest, null ).kind );
    assertEquals( SilexGisClient.BAD_URL, new SilexGisClient( "http://files.example.org", KEY ).download( "/x", dest, null ).kind );
    assertEquals( 0, hits.get() );
  }

  @Test
  public void downloadReportsAProblemOfTheServerAndLeavesNoFile() throws Exception
  {
    File dest = new File( dir, "m.zip" );
    SilexGisClient.Result r = client().download( "/files/missing.zip", dest, null );
    assertEquals( SilexGisClient.PROBLEM, r.kind );
    assertEquals( 404, r.status );
    assertEquals( "survey_upload.not_found", r.code );
    assertFalse( dest.exists() );
    assertFalse( new File( dir, "m.zip.part" ).exists() );
  }

  @Test
  public void downloadReportsARefusedKey() throws Exception
  {
    problems.put( "/files/k.zip", new String[] { "401", "{\"title\":\"Unauthorized\",\"code\":\"auth.invalid_token\"}" } );
    File dest = new File( dir, "k.zip" );
    SilexGisClient.Result r = client().download( "/files/k.zip", dest, null );
    assertEquals( SilexGisClient.KEY_REFUSED, r.kind );
    assertFalse( dest.exists() );
  }

  @Test
  public void downloadReportsAKeyThatTheRouteDoesNotAccept() throws Exception
  {
    problems.put( "/files/k.zip", new String[] { "403", "{\"title\":\"Forbidden\",\"code\":\"auth.access_key_not_accepted\"}" } );
    SilexGisClient.Result r = client().download( "/files/k.zip", new File( dir, "k.zip" ), null );
    assertEquals( SilexGisClient.KEY_NOT_ACCEPTED, r.kind );
  }

  @Test
  public void downloadDoesNotFollowARedirect() throws Exception
  {
    redirects.put( "/files/r.zip", otherBase + "/signed/r.zip" );
    files.put( "other:/signed/r.zip", bytes( 10, 1 ) );
    File dest = new File( dir, "r.zip" );
    SilexGisClient.Result r = client().download( "/files/r.zip", dest, null );
    assertEquals( SilexGisClient.PROBLEM, r.kind );
    assertEquals( 302, r.status );
    assertEquals( 0, otherHits.get() );
    assertFalse( dest.exists() );
  }

  @Test
  public void downloadStopsWhenTheListenerSaysSoAndLeavesNoFile() throws Exception
  {
    files.put( "/files/s.zip", bytes( 400000, 5 ) );
    File dest = new File( dir, "s.zip" );
    final AtomicInteger calls = new AtomicInteger();
    SilexGisClient.Result r = client().download( "/files/s.zip", dest, new SilexGisClient.Progress() {
      @Override public boolean onProgress( long done, long total ) { return calls.incrementAndGet() < 2; }
    } );
    assertEquals( SilexGisClient.CANCELLED, r.kind );
    assertEquals( 2, calls.get() );
    assertFalse( dest.exists() );
    assertFalse( new File( dir, "s.zip.part" ).exists() );
  }

  @Test
  public void downloadKeepsTheFileThatWasThereWhenItIsStopped() throws Exception
  {
    files.put( "/files/s.zip", bytes( 400000, 5 ) );
    File dest = new File( dir, "s.zip" );
    Files.write( dest.toPath(), bytes( 20, 2 ) );
    SilexGisClient.Result r = client().download( "/files/s.zip", dest, new SilexGisClient.Progress() {
      @Override public boolean onProgress( long done, long total ) { return false; }
    } );
    assertEquals( SilexGisClient.CANCELLED, r.kind );
    assertArrayEquals( bytes( 20, 2 ), Files.readAllBytes( dest.toPath() ) );
  }

  @Test
  public void downloadRejectsABodyShorterThanAnnounced() throws Exception
  {
    files.put( "/files/t.zip", bytes( 5000, 5 ) );
    shortBody = 100;
    File dest = new File( dir, "t.zip" );
    SilexGisClient.Result r = client().download( "/files/t.zip", dest, null );
    assertEquals( SilexGisClient.NETWORK, r.kind );
    assertFalse( dest.exists() );
    assertFalse( new File( dir, "t.zip.part" ).exists() );
  }

  @Test
  public void downloadReportsAFileThatCannotBeWritten() throws Exception
  {
    files.put( "/files/w.zip", bytes( 100, 5 ) );
    File dest = new File( new File( dir, "no-such-folder" ), "w.zip" );
    SilexGisClient.Result r = client().download( "/files/w.zip", dest, null );
    assertEquals( SilexGisClient.FILE_ERROR, r.kind );
    assertFalse( r.message().isEmpty() );
    assertFalse( dest.exists() );
  }

  @Test
  public void downloadReportsAServerThatIsNotThere() throws Exception
  {
    SilexGisClient c = new SilexGisClient( "http://127.0.0.1:1", KEY ); // nothing listens on port 1
    File dest = new File( dir, "z.zip" );
    SilexGisClient.Result r = c.download( "/files/z.zip", dest, null );
    assertEquals( SilexGisClient.NETWORK, r.kind );
    assertFalse( dest.exists() );
  }
}
