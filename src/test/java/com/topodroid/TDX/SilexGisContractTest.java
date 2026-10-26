package com.topodroid.TDX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The upload against the answers the SilexGIS server itself recorded (src/test/resources/silexgis/contract-v1,
 * copied from the server's contract recordings): the client reads the ids, the status page and the routes
 * from the shape the server really sends, not from a shape written for the tests.
 * Every value is invented.
 */
public class SilexGisContractTest
{
  private static final String KEY    = "sgx_TestKeyAAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIII";
  private static final String UP     = "/api/v1/survey-uploads";
  private static final String UPLOAD = "0190a000-0000-7000-8000-0000000000a1";
  private static final String CAVE   = "0190a000-0000-7000-8000-0000000000c1";
  private static final String SURVEY = "0190a000-0000-7000-8000-0000000000d1";
  private static final String ZIP    = "pestera_inventata.zip";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private FakeSilexGis server;
  private SilexGisUploader uploader;

  @Before
  public void start() throws IOException
  {
    server = new FakeSilexGis();
    uploader = new SilexGisUploader( new SilexGisClient( server.base(), KEY ), ms -> { } );
  }

  @After
  public void stop() { server.stop(); }

  /** @return a recorded answer, its placeholders replaced by invented values */
  static String recorded( String name ) throws IOException
  {
    String text;
    try ( InputStream is = SilexGisContractTest.class.getClassLoader().getResourceAsStream( "silexgis/contract-v1/" + name + ".json" ) ) {
      if ( is == null ) throw new IOException( "no recorded answer " + name );
      text = new String( FakeSilexGis.readAll( is ), StandardCharsets.UTF_8 );
    }
    text = text.replace( "<upload>", UPLOAD ).replace( "<next-upload>", UPLOAD )
               .replace( "<cave>", CAVE ).replace( "<survey>", SURVEY )
               .replace( "<timestamp>", "2026-10-25T01:32:00Z" ).replace( "<token>", "tok" ).replace( "<marker>", "m1" );
    text = text.replaceAll( "<[a-z0-9-]+>", "0190a000-0000-7000-8000-0000000000ff" ); // the other identifiers
    assertFalse( name + " has a placeholder left", text.contains( "<" ) );
    return text;
  }

  /** listener that records what the upload tells */
  private static class Recorder implements SilexGisUploader.Listener
  {
    SilexGisStatus opened = null;
    final List< String > statuses = new ArrayList<>();
    @Override public void onStep( int step, String name, int k, int n ) { }
    @Override public void onBytes( long sent, long total ) { }
    @Override public void onOpened( SilexGisStatus o ) { opened = o; }
    @Override public void onStatus( SilexGisStatus s ) { statuses.add( s.status ); }
    @Override public boolean isStopped() { return false; }
  }

  private SilexGisFiles files() throws IOException
  {
    File f = tmp.newFile( "archive.zip" );
    try ( FileOutputStream fos = new FileOutputStream( f ) ) { fos.write( "PK-invented-archive".getBytes( StandardCharsets.UTF_8 ) ); }
    SilexGisFiles files = new SilexGisFiles();
    files.add( ZIP, f, SilexGisFiles.ROLE_ARCHIVE );
    return files;
  }

  private void recordedConversation( String last ) throws IOException
  {
    server.on( "GET",  UP + "/capabilities", 200, recorded( "01-capabilities" ) )
          .on( "POST", UP, 201, recorded( "02-open" ) )
          .on( "PUT",  UP + "/" + UPLOAD + "/files/" + ZIP, 200, recorded( "03-put-file" ) )
          .on( "POST", UP + "/" + UPLOAD + "/complete", 202, recorded( "04-complete" ) )
          .on( "GET",  UP + "/" + UPLOAD, 200, recorded( last ) );
  }

  @Test
  public void theRecordedAnswersCarryAnUploadToTheEnd() throws IOException
  {
    recordedConversation( "06-status-succeeded" );
    Recorder rec = new Recorder();
    SilexGisUploader.Outcome out = uploader.run( new SilexGisUpload(), "Pestera Inventata m1", "pestera_inventata", null, "6.5.34", files(), rec );

    assertEquals( SilexGisUploader.SUCCEEDED, out.kind );
    assertEquals( UPLOAD, out.uploadId );
    assertEquals( "/caves/" + CAVE + "/surveys/" + SURVEY + "/uploads/" + UPLOAD, out.statusPageUrl );
    assertTrue( out.completed );

    // the opening answer: the ids are nested, as the server sends them
    assertEquals( CAVE, rec.opened.caveId );
    assertEquals( "Pestera Inventata m1", rec.opened.caveName );
    assertEquals( SURVEY, rec.opened.caveSurveyId );
    assertEquals( "Pestera Inventata m1", rec.opened.caveSurveyTitle );
    assertEquals( "receiving", rec.opened.status );

    // the conversation, with the file sent to the default route
    assertEquals( Arrays.asList(
        "GET " + UP + "/capabilities", "POST " + UP,
        "PUT " + UP + "/" + UPLOAD + "/files/" + ZIP,
        "POST " + UP + "/" + UPLOAD + "/complete",
        "GET " + UP + "/" + UPLOAD ), server.trail() );
    assertEquals( Arrays.asList( "succeeded" ), rec.statuses );

    // the recorded server has no compiler: the request does not ask to compile
    assertFalse( out.compilerAvailable );
    JSONObject body = new JSONObject( server.of( "POST", UP ).get( 0 ).text() );
    assertFalse( body.getJSONObject( "pipeline" ).getBoolean( "compile" ) );

    // the final status
    assertEquals( SURVEY, out.status.caveSurveyId );
    assertEquals( CAVE, out.status.caveId );
    assertEquals( "unpack", out.status.stage );
    assertEquals( 100, out.status.progress );
    assertEquals( "/p/survey/0190a000-0000-7000-8000-0000000000ff", out.status.publicLinkUrl );
    assertEquals( "public", out.status.publicLinkAudience );
    assertNull( out.status.publicLinkRefusal );
    assertTrue( out.status.artefacts.isEmpty() );
  }

  @Test
  public void theRecordedCompiledRunListsItsModelsDrawingsAndLog() throws IOException
  {
    SilexGisStatus st = SilexGisStatus.parse( new JSONObject( recorded( "06b-status-compiled" ) ) );
    assertTrue( st.isSucceeded() );
    assertEquals( "read", st.stage );
    assertEquals( Arrays.asList( "3d", "lox", "plan-pdf", "extended-pdf", "log" ), kinds( st.artefacts ) );
    assertEquals( Arrays.asList( "3d", "lox", "plan-pdf", "extended-pdf", "log" ), kinds( st.downloadable() ) );
    assertTrue( st.withError().isEmpty() );
    for ( SilexGisStatus.Artefact a : st.artefacts ) {
      assertTrue( a.kind, a.contentUrl.startsWith( "/api/v1/files/" ) );
      assertTrue( a.kind, a.size > 0 );
      assertEquals( a.kind, "lox".equals( a.kind ) || "3d".equals( a.kind ), a.isModel() );
    }
    assertEquals( "/p/survey/0190a000-0000-7000-8000-0000000000ff", st.publicLinkUrl );
    assertEquals( "/caves/" + CAVE + "/surveys/" + SURVEY + "/uploads/" + UPLOAD, st.statusPageUrl );
  }

  @Test
  public void anOutputNotProducedIsToldAndNotOfferedForDownload() throws IOException
  {
    // the recorded compiled run, with the plan PDF missing as the server tells it: an error and no file
    JSONObject json = new JSONObject( recorded( "06b-status-compiled" ) );
    JSONObject pdf = json.getJSONArray( "artefacts" ).getJSONObject( 2 );
    assertEquals( "plan-pdf", pdf.getString( "kind" ) );
    pdf.put( "sizeBytes", JSONObject.NULL ).put( "contentUrl", JSONObject.NULL )
       .put( "error", new JSONObject().put( "code", "survey_pipeline.output_missing" ).put( "message", "Therion did not produce this output." ) );
    SilexGisStatus st = SilexGisStatus.parse( json );
    assertEquals( Arrays.asList( "3d", "lox", "extended-pdf", "log" ), kinds( st.downloadable() ) );
    assertEquals( 1, st.withError().size() );
    SilexGisStatus.Artefact missing = st.withError().get( 0 );
    assertEquals( "plan-pdf", missing.kind );
    assertEquals( "survey_pipeline.output_missing", missing.errorCode );
    assertEquals( "Therion did not produce this output.", missing.errorMessage );
    assertEquals( -1, missing.size );
  }

  @Test
  public void theRefusalOfAPublicLinkIsRead() throws IOException
  {
    JSONObject json = new JSONObject( recorded( "06-status-succeeded" ) );
    json.put( "publicLink", JSONObject.NULL ).put( "publicLinkRefusal", "protected-cave" );
    SilexGisStatus st = SilexGisStatus.parse( json );
    assertNull( st.publicLinkUrl );
    assertEquals( "protected-cave", st.publicLinkRefusal );
  }

  private static List< String > kinds( List< SilexGisStatus.Artefact > artefacts )
  {
    ArrayList< String > ret = new ArrayList<>();
    for ( SilexGisStatus.Artefact a : artefacts ) ret.add( a.kind );
    return ret;
  }

  @Test
  public void theRecordedFailureCarriesItsCode() throws IOException
  {
    recordedConversation( "08-status-failed" );
    SilexGisUploader.Outcome out = uploader.run( new SilexGisUpload(), "Pestera Inventata m1", "pestera_inventata", null, "6.5.34", files(), new Recorder() );
    assertEquals( SilexGisUploader.FAILED, out.kind );
    assertEquals( "survey_pipeline.archive_invalid", out.status.errorCode );
    assertTrue( out.status.errorMessage.contains( "manifest" ) );
    assertEquals( "/caves/" + CAVE + "/surveys/" + SURVEY + "/uploads/" + UPLOAD, out.status.statusPageUrl );
  }

  @Test
  public void theRecordedCapabilitiesAreRead() throws IOException
  {
    JSONObject caps = new JSONObject( recorded( "01-capabilities" ) );
    assertTrue( SilexGisUploader.caveCreationAllowed( caps ) );
    assertEquals( Arrays.asList( SilexGisUpload.VISIBILITIES ), SilexGisUploader.caveVisibilities( caps ) );
    assertEquals( 262144000L, caps.getLong( "maxFileBytes" ) );
  }

  /** assert that the request the client sends has every member of the recorded request, with the same value
   * where the value is the client's own (the sizes and the title of the recording are those of its archive)
   */
  private static void assertSentAsRecorded( JSONObject recorded, JSONObject sent ) throws IOException
  {
    for ( String name : JSONObject.getNames( recorded ) ) {
      assertTrue( "the request carries " + name, sent.has( name ) );
    }
    assertEquals( recorded.getString( "source" ), sent.getString( "source" ) );
    assertEquals( recorded.getJSONObject( "client" ).getString( "name" ), sent.getJSONObject( "client" ).getString( "name" ) );
    assertEquals( recorded.getString( "surveyName" ), sent.getString( "surveyName" ) );
    JSONObject rf = recorded.getJSONArray( "files" ).getJSONObject( 0 );
    JSONObject sf = sent.getJSONArray( "files" ).getJSONObject( 0 );
    for ( String name : JSONObject.getNames( rf ) ) assertTrue( "a file carries " + name, sf.has( name ) );
    assertEquals( rf.getString( "role" ), sf.getString( "role" ) );
  }

  @Test
  public void theOpeningRequestHasTheShapeOfTheRecordedOne() throws IOException
  {
    recordedConversation( "06-status-succeeded" );
    SilexGisUpload upload = new SilexGisUpload();
    upload.setLinkChoice( SilexGisUpload.LINK_PUBLIC ); // the recorded tool picked a link for anyone
    uploader.run( upload, "Pestera Inventata m1", "pestera_inventata", "Invented survey for the recorded contract", "6.5.34", files(), new Recorder() );
    JSONObject sent = new JSONObject( server.of( "POST", UP ).get( 0 ).text() );
    JSONObject recorded = new JSONObject( recorded( "02-open.request" ) );
    assertSentAsRecorded( recorded, sent );
    assertFalse( "no target: the server makes the cave", sent.has( "cave" ) || sent.has( "caveSurvey" ) );
    assertEquals( "the picked link is sent as the recorded one", recorded.getJSONObject( "publicLink" ).toString(), sent.getJSONObject( "publicLink" ).toString() );
  }

  @Test
  public void anOpeningThatLeavesTheLinkToTheAccountSendsNoLinkMember() throws IOException
  {
    recordedConversation( "06-status-succeeded" );
    uploader.run( new SilexGisUpload(), "Pestera Inventata m1", "pestera_inventata", null, "6.5.34", files(), new Recorder() );
    JSONObject sent = new JSONObject( server.of( "POST", UP ).get( 0 ).text() );
    assertFalse( "absent: the account preferences decide, as the server records", sent.has( "publicLink" ) );
  }

  @Test
  public void theRecordedMakingOfALinkIsReadWithItsAudience() throws IOException
  {
    server.on( "POST", UP + "/" + UPLOAD + "/public-link", 201, recorded( "13-make-public-link" ) );
    SilexGisLink link = SilexGisLink.of( null );
    assertEquals( SilexGisLink.MADE, link.make( new SilexGisClient( server.base(), KEY ), UPLOAD, "organization", false ) );
    assertEquals( "/p/survey/0190a000-0000-7000-8000-0000000000ff", link.url );
    assertEquals( "organization", link.audience );
    assertTrue( link.isOrganization() );
    JSONObject sent = new JSONObject( server.of( "POST", UP + "/" + UPLOAD + "/public-link" ).get( 0 ).text() );
    JSONObject recorded = new JSONObject( recorded( "13-make-public-link.request" ) );
    for ( String name : JSONObject.getNames( recorded ) ) assertEquals( name, recorded.get( name ), sent.get( name ) );
    assertEquals( recorded.length(), sent.length() );
  }

  @Test
  public void theNextVersionIsOpenedByTheSurveyTheServerAnswered() throws IOException
  {
    server.on( "GET",  UP + "/capabilities", 200, recorded( "01-capabilities" ) )
          .on( "POST", UP, 201, recorded( "07-open-next-version" ) )
          .on( "PUT",  UP + "/" + UPLOAD + "/files/" + ZIP, 200, recorded( "03-put-file" ) )
          .on( "POST", UP + "/" + UPLOAD + "/complete", 202, recorded( "05-status-queued" ) )
          .on( "GET",  UP + "/" + UPLOAD, 200, recorded( "05-status-queued" ) )
          .on( "GET",  UP + "/" + UPLOAD, 200, recorded( "08-status-failed" ) );
    SilexGisUpload upload = new SilexGisUpload();
    upload.setCaveSurvey( SURVEY, "Pestera Inventata m1", CAVE, "Pestera Inventata m1" );
    Recorder rec = new Recorder();
    SilexGisUploader.Outcome out = uploader.run( upload, "Pestera Inventata m1", "pestera_inventata", null, "6.5.34", files(), rec );

    JSONObject sent = new JSONObject( server.of( "POST", UP ).get( 0 ).text() );
    JSONObject recordedRequest = new JSONObject( recorded( "07-open-next-version.request" ) );
    assertSentAsRecorded( recordedRequest, sent );
    assertEquals( recordedRequest.getJSONObject( "caveSurvey" ).getString( "id" ), sent.getJSONObject( "caveSurvey" ).getString( "id" ) );
    assertFalse( sent.has( "cave" ) || sent.has( "newCave" ) );
    assertEquals( SURVEY, rec.opened.caveSurveyId );
    assertEquals( Arrays.asList( "queued", "failed" ), rec.statuses );
    assertEquals( SilexGisUploader.FAILED, out.kind );
  }

  @Test
  public void theRecordedQueuedAndRerunAnswersAreNotFinished() throws IOException
  {
    for ( String name : new String[] { "05-status-queued", "10-rerun" } ) {
      SilexGisStatus st = SilexGisStatus.parse( new JSONObject( recorded( name ) ) );
      assertEquals( name, "queued", st.status );
      assertFalse( name, st.isFinished() );
      assertNull( name, st.stage );
      assertEquals( name, SURVEY, st.caveSurveyId );
    }
  }

  @Test
  public void theRecordedListIsReadForTheImport() throws IOException
  {
    JSONObject json = new JSONObject( recorded( "09-list" ) );
    for ( int k = 0; k < json.getJSONArray( "items" ).length(); ++k ) {
      assertEquals( "the list is asked by the source the items carry", "topodroid", json.getJSONArray( "items" ).getJSONObject( k ).getString( "source" ) );
    }
    ArrayList< SilexGisClient.Upload > ups = SilexGisClient.parseUploads( json );
    assertEquals( 2, ups.size() );
    assertEquals( 2, SilexGisClient.totalUploads( json ) );
    SilexGisClient.Upload up = ups.get( 1 );
    assertEquals( UPLOAD, up.id );
    assertEquals( CAVE, up.caveId );
    assertEquals( "Pestera Inventata m1", up.caveName );
    assertEquals( SURVEY, up.surveyId );
    assertEquals( "Pestera Inventata m1", up.title );
    assertEquals( "pestera_inventata", up.surveyName );
    assertEquals( "2026-10-25T01:32:00Z", up.version );
    assertEquals( ZIP, up.archiveName );
    assertEquals( 2276L, up.size );
    assertEquals( "/api/v1/files/0190a000-0000-7000-8000-0000000000ff/content?token=tok", up.archiveUrl );
    assertTrue( up.therion.isEmpty() );
  }

  @Test
  public void theListIsAskedForTheTopoDroidUploads() throws IOException
  {
    server.on( "GET", UP, 200, recorded( "09-list" ) );
    SilexGisClient client = new SilexGisClient( server.base(), KEY );
    assertTrue( client.listUploads( "topodroid", 100 ).isOk() );
    assertEquals( "source=topodroid&pageSize=100", server.of( "GET", UP ).get( 0 ).query );
  }

  @Test
  public void theRecordedRefusalsAreTold() throws IOException
  {
    SilexGisClient client = new SilexGisClient( server.base(), KEY );
    server.on( "GET", UP + "/capabilities", 401, recorded( "11-errors-unauthenticated" ) )
          .on( "GET", "/api/v1/me/access-keys", 403, recorded( "11-errors-access-key-not-accepted" ) )
          .on( "GET", UP + "/" + UPLOAD, 426, recorded( "11-errors-contract-unsupported" ) )
          .on( "DELETE", UP + "/" + UPLOAD, 409, recorded( "11-errors-not-receiving" ) );
    SilexGisClient.Result r = client.capabilities();
    assertEquals( SilexGisClient.KEY_REFUSED, r.kind );
    assertEquals( "auth.unauthenticated", r.code );
    r = client.request( "GET", "/api/v1/me/access-keys", null );
    assertEquals( SilexGisClient.KEY_NOT_ACCEPTED, r.kind );
    r = client.uploadStatus( UPLOAD );
    assertEquals( SilexGisClient.PROBLEM, r.kind );
    assertEquals( 426, r.status );
    assertEquals( "survey_upload.contract_unsupported", r.code );
    assertTrue( r.message().contains( "version 1" ) );
    r = client.abandonUpload( UPLOAD );
    assertEquals( SilexGisClient.PROBLEM, r.kind );
    assertEquals( "survey_upload.not_receiving", r.code );
  }

  @Test
  public void everyRecordedRefusalIsReadWithItsCode() throws IOException
  {
    String[] cases = { "cave-ambiguous", "cave-not-found", "cave-type-unknown", "content-mismatch", "expired",
      "files-invalid", "files-missing", "file-unknown", "not-found", "output-unknown", "public-link-not-found",
      "run-in-progress", "size-mismatch", "source-unsupported", "survey-not-found", "visibility-invalid", "not-receiving" };
    SilexGisClient client = new SilexGisClient( server.base(), KEY );
    for ( int k = 0; k < cases.length; ++k ) {
      JSONObject problem = new JSONObject( recorded( "11-errors-" + cases[k] ) );
      String path = UP + "/case-" + k;
      server.on( "GET", path, problem.getInt( "status" ), problem.toString() );
      SilexGisClient.Result r = client.request( "GET", path, null );
      assertEquals( cases[k], SilexGisClient.PROBLEM, r.kind );
      assertEquals( cases[k], problem.getInt( "status" ), r.status );
      assertEquals( cases[k], problem.getString( "code" ), r.code );
      assertFalse( cases[k], r.message().isEmpty() );
    }
  }

  @Test
  public void anAbandonedUploadIsAnsweredWithNoContentAndThatIsASuccess() throws IOException
  {
    server.on( "DELETE", UP + "/" + UPLOAD, 204, "" );
    SilexGisClient.Result r = new SilexGisClient( server.base(), KEY ).abandonUpload( UPLOAD );
    assertEquals( SilexGisClient.OK, r.kind );
    assertEquals( 204, r.status );
  }

  @Test
  public void theCaveSearchReadsThePageOfTheCaveList() throws IOException
  {
    // the shape of GET /api/v1/caves: a page of cave list items { items, page, pageSize, totalItems } (invented caves)
    JSONObject page = new JSONObject(
        "{\"items\":["
      + "{\"id\":\"0190a000-0000-7000-8000-00000000c001\",\"kind\":\"cave\",\"name\":\"Pestera Inventata\",\"identificationCode\":\"INV-1\","
      + "\"caveTypeId\":1,\"region\":\"Test region\",\"locationProtected\":false,\"entranceCount\":1,\"geom\":null,\"visibility\":\"cavingGroup\"},"
      + "{\"id\":\"0190a000-0000-7000-8000-00000000c002\",\"kind\":\"cave\",\"name\":\"Avenul Fictiv\",\"identificationCode\":null,\"region\":null},"
      + "{\"id\":null,\"name\":\"no id\"}"
      + "],\"page\":1,\"pageSize\":20,\"totalItems\":3}" );
    ArrayList< String[] > caves = SilexGisClient.parseCaves( page );
    assertEquals( 2, caves.size() );
    assertEquals( "0190a000-0000-7000-8000-00000000c001", caves.get( 0 )[0] );
    assertEquals( "Pestera Inventata", caves.get( 0 )[1] );
    assertEquals( "Pestera Inventata (INV-1) - Test region", caves.get( 0 )[2] );
    assertEquals( "Avenul Fictiv", caves.get( 1 )[2] );
    assertTrue( SilexGisClient.parseCaves( new JSONObject( "{}" ) ).isEmpty() );
    assertTrue( SilexGisClient.parseCaves( null ).isEmpty() );
  }
}
