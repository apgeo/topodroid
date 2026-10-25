package com.topodroid.TDX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The request that opens a SilexGIS upload, and the remembered forms of the upload options.
 * Survey names, titles and ids are invented.
 */
public class SilexGisUploadTest
{
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final List< String > OUTPUTS = Arrays.asList( "lox", "3d", "plan-pdf", "extended-pdf", "kml" );

  private List< SilexGisFiles.Item > files() throws IOException
  {
    SilexGisFiles files = new SilexGisFiles();
    files.add( "pestera_inventata.zip", file( "a.zip", 12 ), SilexGisFiles.ROLE_ARCHIVE );
    files.add( "pestera_inventata.th", file( "b.th", 5 ), SilexGisFiles.ROLE_TH );
    files.add( "pestera_inventata-1p.th2", file( "c.th2", 3 ), SilexGisFiles.ROLE_TH2 );
    return files.items();
  }

  private File file( String name, int size ) throws IOException
  {
    File f = new File( tmp.getRoot(), name ); // written again by each call
    try ( FileOutputStream fos = new FileOutputStream( f ) ) {
      for ( int k = 0; k < size; ++k ) fos.write( 'x' );
    }
    return f;
  }

  @Test
  public void withoutATargetTheRequestNamesNoCaveAndTheServerMakesOneFromTheTitle() throws IOException
  {
    SilexGisUpload u = new SilexGisUpload();
    JSONObject body = u.openRequest( "pestera inventata", "pestera_inventata", "  invented sample  ", "6.5.34", files(), OUTPUTS, true );
    assertEquals( "topodroid", body.getString( "source" ) );
    assertEquals( "TopoDroid", body.getJSONObject( "client" ).getString( "name" ) );
    assertEquals( "6.5.34", body.getJSONObject( "client" ).getString( "version" ) );
    assertFalse( body.has( "cave" ) );
    assertFalse( body.has( "caveSurvey" ) );
    assertFalse( body.has( "newCave" ) );
    assertEquals( "pestera inventata", body.getString( "surveyTitle" ) );
    assertEquals( "pestera_inventata", body.getString( "surveyName" ) );
    assertEquals( "invented sample", body.getString( "description" ) );
    JSONArray f = body.getJSONArray( "files" );
    assertEquals( 3, f.length() );
    assertEquals( "pestera_inventata.zip", f.getJSONObject( 0 ).getString( "name" ) );
    assertEquals( 12, f.getJSONObject( 0 ).getLong( "size" ) );
    assertEquals( "archive", f.getJSONObject( 0 ).getString( "role" ) );
    assertEquals( "therionSource", f.getJSONObject( 1 ).getString( "role" ) );
    assertEquals( "therionSketch", f.getJSONObject( 2 ).getString( "role" ) );
    JSONObject pipeline = body.getJSONObject( "pipeline" );
    assertTrue( pipeline.getBoolean( "compile" ) );
    assertEquals( "[\"lox\",\"3d\",\"plan-pdf\",\"extended-pdf\"]", pipeline.getJSONArray( "outputs" ).toString() );
    assertFalse( "the pipeline never sets the current model", pipeline.has( "makeCurrentModel" ) );
    assertEquals( "cavingGroup", body.getString( "documentsVisibility" ) );
    assertFalse( "the server reads no public link switch: the account preference decides", body.has( "publicLink" ) );
  }

  @Test
  public void withoutATargetTheCaveTheServerMakesHasTheVisibilityOfTheSwitches() throws IOException
  {
    SilexGisUpload u = new SilexGisUpload();
    u.visibility = "private";
    JSONObject body = u.openRequest( "pestera inventata", "pestera_inventata", null, "6.5.34", files(), OUTPUTS, true );
    JSONObject cave = body.getJSONObject( "newCave" );
    assertEquals( "private", cave.getString( "visibility" ) );
    assertFalse( "no name: the server names the cave after the survey title", cave.has( "name" ) );
    assertFalse( cave.has( "cavingGroupId" ) );
    assertFalse( body.has( "cave" ) || body.has( "caveSurvey" ) );
    assertEquals( "private", body.getString( "documentsVisibility" ) );

    u.visibility = "public";
    body = u.openRequest( "t", "n", null, "v", files(), null, true );
    assertEquals( "public", body.getJSONObject( "newCave" ).getString( "visibility" ) );

    u.visibility = "cavingGroup"; // the server's default: the club of the account, else private
    body = u.openRequest( "t", "n", null, "v", files(), null, true );
    assertFalse( body.has( "newCave" ) );
    assertEquals( "cavingGroup", body.getString( "documentsVisibility" ) );
  }

  @Test
  public void noOutputsMeansTheServerDefaultAndIsRemembered() throws IOException
  {
    SilexGisUpload u = new SilexGisUpload();
    u.outputs = "";
    JSONObject body = u.openRequest( "t", "n", null, "v", files(), OUTPUTS, true );
    assertTrue( body.getJSONObject( "pipeline" ).getBoolean( "compile" ) );
    assertFalse( "the server default", body.getJSONObject( "pipeline" ).has( "outputs" ) );
    assertEquals( "an empty list is remembered as it is", "", u.copy().outputs );
  }

  @Test
  public void aPickedCaveASurveyOrANewCaveIsTheOnlyTargetOfTheRequest() throws IOException
  {
    SilexGisUpload u = new SilexGisUpload();
    u.setCave( "0190a000-0000-7000-8000-00000000c001", "Pestera Inventata" );
    JSONObject body = u.openRequest( "t", "n", null, "v", files(), null, true );
    assertEquals( "0190a000-0000-7000-8000-00000000c001", body.getJSONObject( "cave" ).getString( "id" ) );
    assertFalse( body.has( "caveSurvey" ) || body.has( "newCave" ) );
    assertFalse( "no description when the survey has none", body.has( "description" ) );

    u.setCaveSurvey( "0190a000-0000-7000-8000-00000000d001", "galeria noua", "c1", "Pestera Inventata" );
    body = u.openRequest( "t", "n", "", "v", files(), null, true );
    assertEquals( "0190a000-0000-7000-8000-00000000d001", body.getJSONObject( "caveSurvey" ).getString( "id" ) );
    assertFalse( body.has( "cave" ) || body.has( "newCave" ) );

    u.setNewCave( "Pestera Noua", "public", null );
    body = u.openRequest( "t", "n", null, "v", files(), null, true );
    JSONObject cave = body.getJSONObject( "newCave" );
    assertEquals( "Pestera Noua", cave.getString( "name" ) );
    assertEquals( "public", cave.getString( "visibility" ) );
    assertFalse( "the only club of the account is the server's to take", cave.has( "cavingGroupId" ) );
    assertFalse( body.has( "cave" ) || body.has( "caveSurvey" ) );

    u.setNewCave( "Pestera Noua", "cavingGroup", "club-2" );
    body = u.openRequest( "t", "n", null, "v", files(), null, true );
    assertEquals( "club-2", body.getJSONObject( "newCave" ).getString( "cavingGroupId" ) );
  }

  @Test
  public void aServerThatDoesNotCompileIsNotAskedToAndOnlyItsOutputsAreAsked() throws IOException
  {
    SilexGisUpload u = new SilexGisUpload();
    u.outputs = "LOX, plan-svg  3d,lox";
    JSONObject body = u.openRequest( "t", "n", null, "v", files(), null, false );
    assertFalse( body.getJSONObject( "pipeline" ).getBoolean( "compile" ) );
    assertFalse( "nothing to produce without a compiler", body.getJSONObject( "pipeline" ).has( "outputs" ) );
    body = u.openRequest( "t", "n", null, "v", files(), null, true );
    assertEquals( "outputs not known: all asked", "[\"lox\",\"plan-svg\",\"3d\"]", body.getJSONObject( "pipeline" ).getJSONArray( "outputs" ).toString() );
    assertEquals( Arrays.asList( "plan-svg" ), u.droppedOutputs( OUTPUTS ) );
    body = u.openRequest( "t", "n", null, "v", files(), OUTPUTS, true );
    assertEquals( "[\"lox\",\"3d\"]", body.getJSONObject( "pipeline" ).getJSONArray( "outputs" ).toString() );
    u.outputs = "svx";
    body = u.openRequest( "t", "n", null, "v", files(), OUTPUTS, true );
    assertFalse( "nothing producible: the server default", body.getJSONObject( "pipeline" ).has( "outputs" ) );
    u.outputs = "lox";
    u.compile = false;
    body = u.openRequest( "t", "n", null, "v", files(), OUTPUTS, true );
    assertFalse( body.getJSONObject( "pipeline" ).getBoolean( "compile" ) );
    assertFalse( body.getJSONObject( "pipeline" ).has( "outputs" ) );
  }

  @Test
  public void aLongDescriptionIsCutToWhatTheServerKeeps() throws IOException
  {
    StringBuilder sb = new StringBuilder();
    for ( int k = 0; k < 5000; ++k ) sb.append( 'd' );
    JSONObject body = ( new SilexGisUpload() ).openRequest( "t", "n", sb.toString(), "v", files(), null, true );
    assertEquals( SilexGisUpload.MAX_DESCRIPTION, body.getString( "description" ).length() );
  }

  @Test
  public void theSwitchesAndTheTargetSurviveTheirRememberedForm()
  {
    SilexGisUpload u = new SilexGisUpload();
    u.withTh = false; u.withSvx = true; u.withDat = true; u.withCsv = true; u.withKml = true; u.withGpx = true; u.withOut = true;
    u.compile = false; u.publicLink = false; u.outputs = "lox"; u.visibility = "private";
    u.setNewCave( "Pestera Noua", "authenticated", "club-1" );
    SilexGisUpload v = new SilexGisUpload();
    v.fromJson( new JSONObject( u.toJson().toString() ) );
    assertEquals( u.switchesToJson().toString(), v.switchesToJson().toString() );
    assertEquals( SilexGisUpload.TARGET_NEW_CAVE, v.target );
    assertEquals( "Pestera Noua", v.newCaveName );
    assertEquals( "authenticated", v.newCaveVisibility );
    assertEquals( "club-1", v.newCaveClubId );
    SilexGisUpload w = u.copy();
    assertEquals( u.toJson().toString(), w.toJson().toString() );
  }

  @Test
  public void aRememberedTargetThatLacksWhatItNeedsIsAutomatic()
  {
    SilexGisUpload u = new SilexGisUpload();
    u.targetFromJson( new JSONObject( "{\"target\":1}" ) ); // a cave without id
    assertEquals( SilexGisUpload.TARGET_AUTO, u.target );
    u.targetFromJson( new JSONObject( "{\"target\":3,\"newCaveName\":\"x\",\"newCaveVisibility\":\"everybody\"}" ) );
    assertEquals( SilexGisUpload.TARGET_AUTO, u.target );
    assertNull( u.newCaveName );
    u.targetFromJson( new JSONObject( "{\"target\":2,\"caveSurveyId\":\"s1\",\"caveSurveyTitle\":\"t\"}" ) );
    assertEquals( SilexGisUpload.TARGET_CAVE_SURVEY, u.target );
    u.targetFromJson( null );
    assertEquals( SilexGisUpload.TARGET_AUTO, u.target );
    u.switchesFromJson( new JSONObject( "{\"visibility\":\"everybody\"}" ) );
    assertEquals( "an unknown visibility is not taken", "cavingGroup", u.visibility );
  }
}
