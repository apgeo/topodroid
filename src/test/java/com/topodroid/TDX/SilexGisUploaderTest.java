package com.topodroid.TDX;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The conversation of a SilexGIS upload with a local server that plays the contract:
 * capabilities, open, put, complete, status until the end; failures, a refused key, stops.
 * Every answer and file is invented.
 */
public class SilexGisUploaderTest
{
  private static final String KEY = "sgx_TestKeyAAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIII";
  private static final String UP  = "/api/v1/survey-uploads";
  private static final String ID  = "0190a000-0000-7000-8000-0000000000u1";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private FakeSilexGis server;
  private SilexGisClient client;
  private final List< Long > sleeps = new ArrayList<>();
  private SilexGisUploader uploader;

  /** listener that records what the upload tells, and stops when asked */
  private static class Recorder implements SilexGisUploader.Listener
  {
    final List< String > steps = new ArrayList<>();
    final List< String > statuses = new ArrayList<>();
    long lastSent = -1, lastTotal = -1;
    SilexGisStatus opened = null;
    volatile boolean stop = false;
    int stopAfterStatuses = -1; // stop once this many statuses have been seen
    int stopAfterBytes    = -1; // stop at the first progress of a file

    @Override public void onStep( int step, String name, int k, int n ) { steps.add( step + ( name == null ? "" : ":" + name + ":" + k + "/" + n ) ); }
    @Override public void onBytes( long sent, long total ) {
      lastSent = sent; lastTotal = total;
      if ( stopAfterBytes >= 0 ) stop = true;
    }
    @Override public void onOpened( SilexGisStatus o ) { opened = o; }
    @Override public void onStatus( SilexGisStatus s ) {
      statuses.add( s.status + ( s.stage == null ? "" : ":" + s.stage ) );
      if ( stopAfterStatuses > 0 && statuses.size() >= stopAfterStatuses ) stop = true;
    }
    @Override public boolean isStopped() { return stop; }
  }

  @Before
  public void start() throws IOException
  {
    server = new FakeSilexGis();
    client = new SilexGisClient( server.base(), KEY );
    uploader = new SilexGisUploader( client, ms -> sleeps.add( ms ) );
  }

  @After
  public void stop() { server.stop(); }

  private File file( String name, String content ) throws IOException
  {
    File f = tmp.newFile( name );
    try ( FileOutputStream fos = new FileOutputStream( f ) ) { fos.write( content.getBytes( StandardCharsets.UTF_8 ) ); }
    return f;
  }

  private SilexGisFiles files() throws IOException
  {
    SilexGisFiles files = new SilexGisFiles();
    files.add( "pestera_inventata.zip", file( "pestera_inventata.2.zip", "PK-invented-archive" ), SilexGisFiles.ROLE_ARCHIVE );
    files.add( "pestera_inventata.th", file( "pestera_inventata.th", "survey pestera_inventata\nendsurvey\n" ), SilexGisFiles.ROLE_TH );
    files.add( "pestera_inventata-1p.th2", file( "pestera_inventata-1p.th2", "encoding utf-8\n" ), SilexGisFiles.ROLE_TH2 );
    return files;
  }

  private static final String CAPS =
      "{\"contractVersion\":1,\"sources\":[\"topodroid\"],\"maxFileBytes\":262144000,\"maxFilesPerUpload\":64,"
    + "\"acceptedExtensions\":[\".zip\",\".th\",\".th2\",\".svx\",\".csv\"],"
    + "\"compiler\":{\"available\":true,\"version\":\"therion 6.3.4\",\"outputs\":[\"lox\",\"3d\",\"plan-pdf\",\"extended-pdf\"]},"
    + "\"caveCreation\":{\"allowed\":true},\"pollIntervalSeconds\":2}";

  private static final String OPENED =
      "{\"id\":\"" + ID + "\",\"status\":\"receiving\",\"caveId\":\"c1\",\"caveCreated\":true,\"caveSurveyId\":\"s1\","
    + "\"version\":\"2026-10-25T01:32:00Z\",\"statusPageUrl\":\"/caves/c1/surveys/s1/uploads/u1\","
    + "\"files\":["
    + "{\"name\":\"pestera_inventata.zip\",\"role\":\"archive\",\"status\":\"declared\",\"putUrl\":\"" + UP + "/" + ID + "/files/pestera_inventata.zip\"},"
    + "{\"name\":\"pestera_inventata.th\",\"role\":\"therionSource\",\"status\":\"declared\",\"putUrl\":\"" + UP + "/" + ID + "/files/pestera_inventata.th\"}"
    + "],\"expiresAt\":\"2026-10-26T01:32:00Z\"}";

  private static String status( String st, String stage, int progress, String extra )
  {
    return "{\"id\":\"" + ID + "\",\"status\":\"" + st + "\",\"stage\":" + ( stage == null ? "null" : "\"" + stage + "\"" )
      + ",\"progress\":" + progress + ",\"message\":\"step\",\"cave\":{\"id\":\"c1\",\"name\":\"pestera inventata\"},"
      + "\"caveSurvey\":{\"id\":\"s1\",\"title\":\"pestera inventata\"},\"statusPageUrl\":\"/caves/c1/surveys/s1/uploads/u1\"" + extra + "}";
  }

  private void contract() { contract( CAPS ); }

  private void contract( String caps )
  {
    server.on( "GET",  UP + "/capabilities", 200, caps )
          .on( "POST", UP, 201, OPENED )
          .on( "PUT",  UP + "/" + ID + "/files/pestera_inventata.zip", 200, "{\"name\":\"pestera_inventata.zip\",\"status\":\"received\"}" )
          .on( "PUT",  UP + "/" + ID + "/files/pestera_inventata.th", 200, "{\"name\":\"pestera_inventata.th\",\"status\":\"received\"}" )
          .on( "PUT",  UP + "/" + ID + "/files/pestera_inventata-1p.th2", 200, "{\"name\":\"pestera_inventata-1p.th2\",\"status\":\"received\"}" )
          .on( "POST", UP + "/" + ID + "/complete", 202, "{\"id\":\"" + ID + "\",\"status\":\"queued\",\"stage\":null,\"progress\":0}" );
  }

  private SilexGisUploader.Outcome run( SilexGisUpload upload, SilexGisFiles files, Recorder rec )
  {
    return uploader.run( upload, "pestera inventata", "pestera_inventata", "invented", "6.5.34", files, rec );
  }

  // ------------------------------------------------------------------------------------------

  @Test
  public void openPutCompleteThenWatchUntilTheServerHasProcessedIt() throws IOException
  {
    contract();
    server.on( "GET", UP + "/" + ID, 200, status( "queued", null, 0, "" ) )
          .on( "GET", UP + "/" + ID, 200, status( "running", "unpack", 10, "" ) )
          .on( "GET", UP + "/" + ID, 200, status( "running", "compile", 60, "" ) )
          .on( "GET", UP + "/" + ID, 200, status( "succeeded", null, 100,
              ",\"artefacts\":[{\"kind\":\"lox\",\"name\":\"pestera_inventata-20261025T013200Z.lox\",\"contentUrl\":\"/files/t1\"}],"
            + "\"publicLink\":{\"url\":\"/p/survey/tok\"}" ) );
    Recorder rec = new Recorder();
    SilexGisUpload upload = new SilexGisUpload();
    upload.outputs = "lox,3d,plan-svg";
    SilexGisUploader.Outcome out = run( upload, files(), rec );

    assertEquals( SilexGisUploader.SUCCEEDED, out.kind );
    assertEquals( ID, out.uploadId );
    assertEquals( "/caves/c1/surveys/s1/uploads/u1", out.statusPageUrl );
    assertEquals( Arrays.asList( "queued", "running:unpack", "running:compile", "succeeded" ), rec.statuses );
    assertEquals( "/p/survey/tok", out.status.publicLinkUrl );
    assertEquals( 1, out.status.artefacts.size() );
    assertEquals( "s1", rec.opened.caveSurveyId );
    assertEquals( "c1", rec.opened.caveId );
    assertEquals( Arrays.asList( "plan-svg" ), out.droppedOutputs );
    assertEquals( Arrays.asList( "6", "7",
        "8:pestera_inventata.zip:1/3", "8:pestera_inventata.th:2/3", "8:pestera_inventata-1p.th2:3/3", "9" ), rec.steps );
    assertEquals( Arrays.asList(
        "GET " + UP + "/capabilities", "POST " + UP,
        "PUT " + UP + "/" + ID + "/files/pestera_inventata.zip",
        "PUT " + UP + "/" + ID + "/files/pestera_inventata.th",
        "PUT " + UP + "/" + ID + "/files/pestera_inventata-1p.th2", // no putUrl in the answer: the default route
        "POST " + UP + "/" + ID + "/complete",
        "GET " + UP + "/" + ID, "GET " + UP + "/" + ID, "GET " + UP + "/" + ID, "GET " + UP + "/" + ID ), server.trail() );
    long pauses = 0;
    for ( long s : sleeps ) pauses += s;
    assertEquals( "the pause between two status requests is the server's interval", 3 * 2000, pauses );

    // the request that opened the upload
    JSONObject body = new JSONObject( server.of( "POST", UP ).get( 0 ).text() );
    assertEquals( "pestera inventata", body.getString( "surveyTitle" ) );
    assertEquals( "pestera_inventata", body.getString( "surveyName" ) );
    assertEquals( 3, body.getJSONArray( "files" ).length() );
    assertEquals( "[\"lox\",\"3d\"]", body.getJSONObject( "pipeline" ).getJSONArray( "outputs" ).toString() );

    // a file is sent as its bytes, with the key and the contract version
    FakeSilexGis.Request put = server.of( "PUT", UP + "/" + ID + "/files/pestera_inventata.zip" ).get( 0 );
    assertArrayEquals( "PK-invented-archive".getBytes( StandardCharsets.UTF_8 ), put.body );
    assertEquals( "Bearer " + KEY, put.headers.get( "authorization" ) );
    assertEquals( "1", put.headers.get( "x-silexgis-contract" ) );
    assertEquals( "application/octet-stream", put.headers.get( "content-type" ) );
    assertEquals( "19", put.headers.get( "content-length" ) );
    assertEquals( 15, rec.lastTotal ); // the last file sent
    assertEquals( 15, rec.lastSent );
  }

  @Test
  public void aFailedRunCarriesTheCodeOfTheServer() throws IOException
  {
    contract();
    server.on( "GET", UP + "/" + ID, 200, status( "running", "compile", 30, "" ) )
          .on( "GET", UP + "/" + ID, 200, status( "failed", "compile", 30,
              ",\"error\":{\"code\":\"survey_pipeline.compile_failed\",\"message\":\"Therion reported 2 errors\",\"detail\":\"line 3\"}" ) );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), new Recorder() );
    assertEquals( SilexGisUploader.FAILED, out.kind );
    assertEquals( "survey_pipeline.compile_failed", out.status.errorCode );
    assertEquals( "Therion reported 2 errors", out.status.errorMessage );
    assertTrue( out.hasUpload() );
  }

  @Test
  public void aKeyRefusedWhileWatchingStopsTheUploadAsARefusedKey() throws IOException
  {
    contract();
    server.on( "GET", UP + "/" + ID, 200, status( "running", "unpack", 10, "" ) )
          .on( "GET", UP + "/" + ID, 401, "{\"title\":\"Unauthorized\",\"status\":401}" );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), new Recorder() );
    assertEquals( SilexGisUploader.KEY_REFUSED, out.kind );
    assertEquals( 401, out.result.status );
    assertEquals( "running", out.status.status );
  }

  @Test
  public void aKeyRefusedAtTheStartSendsNothing() throws IOException
  {
    server.on( "GET", UP + "/capabilities", 401, "{\"title\":\"Unauthorized\",\"status\":401}" );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), new Recorder() );
    assertEquals( SilexGisUploader.KEY_REFUSED, out.kind );
    assertFalse( out.hasUpload() );
    assertEquals( Arrays.asList( "GET " + UP + "/capabilities" ), server.trail() );
  }

  @Test
  public void aFileLargerThanTheServerAcceptsIsRefusedBeforeAnythingIsSent() throws IOException
  {
    server.on( "GET", UP + "/capabilities", 200, CAPS.replace( "262144000", "16" ) );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), new Recorder() );
    assertEquals( SilexGisUploader.FILES, out.kind );
    assertEquals( SilexGisFiles.TOO_LARGE, out.problem.kind );
    assertEquals( "pestera_inventata.zip", out.problem.name );
    assertEquals( 16, out.maxFileBytes );
    assertEquals( Arrays.asList( "GET " + UP + "/capabilities" ), server.trail() );
  }

  @Test
  public void aServerWithoutCompilerIsNotAskedToCompileAndFilesItRefusesAreLeftOut() throws IOException
  {
    contract( CAPS.replace( "\"available\":true", "\"available\":false" ) );
    server.on( "GET", UP + "/" + ID, 200, status( "succeeded", null, 100, "" ) );
    SilexGisFiles files = files();
    files.add( "pestera_inventata.gpx", file( "pestera_inventata.gpx", "<gpx/>" ), SilexGisFiles.ROLE_OTHER );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files, new Recorder() );
    assertEquals( SilexGisUploader.SUCCEEDED, out.kind );
    assertFalse( out.compilerAvailable );
    assertEquals( Arrays.asList( "pestera_inventata.gpx" ), out.droppedFiles );
    JSONObject body = new JSONObject( server.of( "POST", UP ).get( 0 ).text() );
    assertFalse( body.getJSONObject( "pipeline" ).getBoolean( "compile" ) );
    assertEquals( 3, body.getJSONArray( "files" ).length() );
  }

  @Test
  public void aRefusalOfTheServerStopsTheUploadWithItsCode() throws IOException
  {
    server.on( "GET", UP + "/capabilities", 200, CAPS )
          .on( "POST", UP, 400, "{\"title\":\"Bad request\",\"status\":400,\"code\":\"survey_upload.cave_ambiguous\",\"detail\":\"both\"}" );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), new Recorder() );
    assertEquals( SilexGisUploader.REFUSED, out.kind );
    assertEquals( "survey_upload.cave_ambiguous", out.result.code );
    assertEquals( "both", out.result.message() );
    assertFalse( out.hasUpload() );
  }

  @Test
  public void aRefusedFileStopsTheUploadAndNothingIsCompleted() throws IOException
  {
    server.on( "GET",  UP + "/capabilities", 200, CAPS )
          .on( "POST", UP, 201, OPENED )
          .on( "PUT",  UP + "/" + ID + "/files/pestera_inventata.zip", 200, "{}" )
          .on( "PUT",  UP + "/" + ID + "/files/pestera_inventata.th", 400, "{\"status\":400,\"code\":\"survey_upload.content_mismatch\",\"detail\":\"not text\"}" );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), new Recorder() );
    assertEquals( SilexGisUploader.REFUSED, out.kind );
    assertEquals( "survey_upload.content_mismatch", out.result.code );
    assertTrue( server.of( "POST", UP + "/" + ID + "/complete" ).isEmpty() );
    assertEquals( "a refusal is not retried", 1, server.of( "PUT", UP + "/" + ID + "/files/pestera_inventata.th" ).size() );
    assertTrue( server.of( "PUT", UP + "/" + ID + "/files/pestera_inventata-1p.th2" ).isEmpty() );
  }

  @Test
  public void aFileWhoseConnectionDropsIsSentOnceMore() throws IOException
  {
    server.on( "GET",  UP + "/capabilities", 200, CAPS )
          .on( "POST", UP, 201, OPENED )
          .drop( "PUT", UP + "/" + ID + "/files/pestera_inventata.zip" )
          .on( "PUT",  UP + "/" + ID + "/files/pestera_inventata.zip", 200, "{}" )
          .on( "PUT",  UP + "/" + ID + "/files/pestera_inventata.th", 200, "{}" )
          .on( "PUT",  UP + "/" + ID + "/files/pestera_inventata-1p.th2", 200, "{}" )
          .on( "POST", UP + "/" + ID + "/complete", 202, "{\"status\":\"queued\"}" )
          .on( "GET",  UP + "/" + ID, 200, status( "succeeded", null, 100, "" ) );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), new Recorder() );
    assertEquals( SilexGisUploader.SUCCEEDED, out.kind );
    assertEquals( 2, server.of( "PUT", UP + "/" + ID + "/files/pestera_inventata.zip" ).size() );
  }

  @Test
  public void stoppingWhileTheServerProcessesStopsOnlyTheWatching() throws IOException
  {
    contract();
    server.on( "GET", UP + "/" + ID, 200, status( "running", "compile", 50, "" ) );
    Recorder rec = new Recorder();
    rec.stopAfterStatuses = 2;
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), rec );
    assertEquals( SilexGisUploader.CANCELLED, out.kind );
    assertEquals( 2, server.of( "GET", UP + "/" + ID ).size() );
    assertTrue( "the server is not told: it goes on", server.of( "DELETE", UP + "/" + ID ).isEmpty() );
    assertEquals( "running", out.status.status );
    assertTrue( out.hasUpload() );
  }

  @Test
  public void stoppingWhileTheFilesAreSentAbandonsTheUpload() throws IOException
  {
    contract();
    server.on( "DELETE", UP + "/" + ID, 204, "" );
    Recorder rec = new Recorder();
    rec.stopAfterBytes = 0;
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), rec );
    assertEquals( SilexGisUploader.ABANDONED, out.kind );
    assertEquals( 1, server.of( "DELETE", UP + "/" + ID ).size() );
    assertTrue( server.of( "POST", UP + "/" + ID + "/complete" ).isEmpty() );
  }

  @Test
  public void theServerUnreachableWhileWatchingIsToleratedAFewTimes() throws IOException
  {
    contract();
    server.drop( "GET", UP + "/" + ID ).drop( "GET", UP + "/" + ID ).on( "GET", UP + "/" + ID, 200, status( "succeeded", null, 100, "" ) );
    SilexGisUploader.Outcome out = run( new SilexGisUpload(), files(), new Recorder() );
    assertEquals( SilexGisUploader.SUCCEEDED, out.kind );
    assertEquals( 3, server.of( "GET", UP + "/" + ID ).size() );
  }

  // ------------------------------------------------------------------------------------------ artefacts

  @Test
  public void anExpiredContentUrlIsAskedAgainOnceWithTheStatus() throws IOException
  {
    server.on( "GET", "/files/old", 410, "{\"status\":410,\"code\":\"delivery.expired\"}" )
          .on( "GET", UP + "/" + ID, 200, status( "succeeded", null, 100,
              ",\"artefacts\":[{\"kind\":\"lox\",\"name\":\"m.lox\",\"contentUrl\":\"/files/new\"}]" ) )
          .on( "GET", "/files/new", new FakeSilexGis.Answer( 200, "application/octet-stream", "LOX-invented".getBytes( StandardCharsets.UTF_8 ) ) );
    SilexGisStatus.Artefact a = new SilexGisStatus.Artefact();
    a.kind = "lox"; a.name = "m.lox"; a.contentUrl = "/files/old";
    File dest = new File( tmp.getRoot(), "m.lox" );
    SilexGisClient.Result r = uploader.fetchArtefact( ID, a, dest, null );
    assertTrue( r.isOk() );
    assertEquals( "LOX-invented", new String( Files.readAllBytes( dest.toPath() ), StandardCharsets.UTF_8 ) );
    assertEquals( "/files/new", a.contentUrl );
    assertEquals( Arrays.asList( "GET /files/old", "GET " + UP + "/" + ID, "GET /files/new" ), server.trail() );
  }

  @Test
  public void aDownloadThatFailsForAnotherReasonIsNotRetried() throws IOException
  {
    server.on( "GET", "/files/t", 500, "{\"status\":500}" );
    SilexGisStatus.Artefact a = new SilexGisStatus.Artefact();
    a.kind = "lox"; a.name = "m.lox"; a.contentUrl = "/files/t";
    SilexGisClient.Result r = uploader.fetchArtefact( ID, a, new File( tmp.getRoot(), "m.lox" ), null );
    assertFalse( r.isOk() );
    assertEquals( 500, r.status );
    assertEquals( 1, server.trail().size() );
    assertFalse( new File( tmp.getRoot(), "m.lox" ).exists() );
  }

  @Test
  public void putUrlsAreReadFromTheOpeningAnswerAndNamesAreEncodedInTheDefaultRoute()
  {
    assertEquals( 2, SilexGisUploader.putUrls( new JSONObject( OPENED ) ).size() );
    assertTrue( SilexGisUploader.putUrls( null ).isEmpty() );
    assertEquals( UP + "/u%201/files/pe%C8%99tera%20inventat%C4%83.zip", SilexGisClient.filePath( "u 1", "peștera inventată.zip" ) );
    assertEquals( "/api/v1/caves?names=Pe%C8%99tera+I&search=Pe%C8%99tera+I&pageSize=20", SilexGisClient.cavesSearchPath( " Peștera I ", 20 ) );
  }

  @Test
  public void aPutToAnotherHostIsRefusedSoTheKeyStaysWithTheServer() throws IOException
  {
    SilexGisClient.Result r = client.putFile( "http://127.0.0.2:9/x", file( "x.th", "x" ), null );
    assertEquals( SilexGisClient.BAD_URL, r.kind );
    assertNotNull( r );
    assertNull( r.json );
  }
}
