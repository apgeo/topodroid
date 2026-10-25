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
    assertTrue( "the server answers no putUrl: the default route", SilexGisUploader.putUrls( new JSONObject( recorded( "02-open" ) ) ).isEmpty() );

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
    assertNull( out.status.publicLinkUrl );
    assertTrue( out.status.artefacts.isEmpty() );
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
}
