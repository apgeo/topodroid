package com.topodroid.TDX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The status row of a SilexGIS upload, as the contract draft shapes it. Values are invented.
 */
public class SilexGisStatusTest
{
  static final String RUNNING =
      "{\"id\":\"u1\",\"status\":\"running\",\"stage\":\"compile\",\"progress\":62,\"message\":\"Compiling the plan at 1:500\","
    + "\"error\":null,\"cave\":{\"id\":\"c1\",\"name\":\"Pestera Inventata\",\"url\":\"/caves/c1\"},"
    + "\"artefacts\":[],\"caveSurvey\":{\"id\":\"s1\",\"title\":\"pestera inventata\"},\"version\":\"2026-10-25T01:32:00Z\","
    + "\"statusPageUrl\":\"/caves/c1/surveys/s1/uploads/u1\",\"publicLink\":null}";

  static final String SUCCEEDED =
      "{\"id\":\"u1\",\"status\":\"succeeded\",\"stage\":null,\"progress\":100,\"message\":null,\"error\":null,"
    + "\"cave\":{\"id\":\"c1\",\"name\":\"Pestera Inventata\"},"
    + "\"artefacts\":["
    + "{\"kind\":\"lox\",\"name\":\"pestera_inventata-20261025T013200Z.lox\",\"sizeBytes\":2048,\"contentUrl\":\"/files/t1\",\"viewUrl\":\"/caves/c1/3d\"},"
    + "{\"kind\":\"plan-pdf\",\"name\":\"pestera_inventata-20261025T013200Z-plan.pdf\",\"contentUrl\":\"/files/t2\"},"
    + "{\"kind\":\"3d\",\"name\":\"pestera_inventata-20261025T013200Z.3d\",\"sizeBytes\":1024,\"contentUrl\":null}"
    + "],\"caveSurvey\":{\"id\":\"s1\",\"title\":\"pestera inventata\"},"
    + "\"statusPageUrl\":\"/caves/c1/surveys/s1/uploads/u1\",\"publicLink\":{\"url\":\"/p/survey/tok\",\"createdAt\":\"2026-10-25T01:33:00Z\"}}";

  static final String FAILED =
      "{\"id\":\"u1\",\"status\":\"failed\",\"stage\":\"compile\",\"progress\":40,"
    + "\"error\":{\"code\":\"survey_pipeline.compile_failed\",\"message\":\"Therion reported 2 errors\",\"detail\":\"line 3\"},"
    + "\"artefacts\":[{\"kind\":\"log\",\"name\":\"x.log\",\"contentUrl\":\"/files/t3\"}],\"publicLinkRefusal\":\"the cave is protected\"}";

  @Test
  public void aRunningUploadSaysWhereItHasGot()
  {
    SilexGisStatus st = SilexGisStatus.parse( new JSONObject( RUNNING ) );
    assertEquals( "u1", st.id );
    assertEquals( "running", st.status );
    assertFalse( st.isFinished() );
    assertEquals( "compile", st.stage );
    assertEquals( 62, st.progress );
    assertEquals( "Compiling the plan at 1:500", st.message );
    assertNull( st.errorCode );
    assertEquals( "c1", st.caveId );
    assertEquals( "Pestera Inventata", st.caveName );
    assertEquals( "s1", st.caveSurveyId );
    assertEquals( "pestera inventata", st.caveSurveyTitle );
    assertEquals( "2026-10-25T01:32:00Z", st.version );
    assertEquals( "/caves/c1/surveys/s1/uploads/u1", st.statusPageUrl );
    assertNull( st.publicLinkUrl );
    assertTrue( st.artefacts.isEmpty() );
  }

  @Test
  public void aSucceededUploadListsItsArtefactsAndItsPublicLink()
  {
    SilexGisStatus st = SilexGisStatus.parse( new JSONObject( SUCCEEDED ) );
    assertTrue( st.isFinished() );
    assertTrue( st.isSucceeded() );
    assertNull( "a null stage is no stage", st.stage );
    assertEquals( 3, st.artefacts.size() );
    SilexGisStatus.Artefact lox = st.artefacts.get( 0 );
    assertEquals( "lox", lox.kind );
    assertEquals( 2048, lox.size );
    assertEquals( "/files/t1", lox.contentUrl );
    assertTrue( lox.isModel() );
    assertFalse( st.artefacts.get( 1 ).isModel() );
    assertEquals( "no size given", -1, st.artefacts.get( 1 ).size );
    assertEquals( "an artefact without a content URL cannot be downloaded", 2, st.downloadable().size() );
    assertEquals( "/p/survey/tok", st.publicLinkUrl );
    SilexGisStatus other = SilexGisStatus.parse( new JSONObject( SUCCEEDED ) );
    assertSame( other.artefacts.get( 1 ), other.find( st.artefacts.get( 1 ) ) );
    SilexGisStatus.Artefact unknown = new SilexGisStatus.Artefact();
    unknown.kind = "kml";
    assertNull( other.find( unknown ) );
  }

  @Test
  public void aFailedUploadCarriesTheCodeAndWhyThereIsNoPublicLink()
  {
    SilexGisStatus st = SilexGisStatus.parse( new JSONObject( FAILED ) );
    assertTrue( st.isFinished() );
    assertFalse( st.isSucceeded() );
    assertEquals( "survey_pipeline.compile_failed", st.errorCode );
    assertEquals( "Therion reported 2 errors", st.errorMessage );
    assertEquals( "the cave is protected", st.publicLinkRefusal );
    assertEquals( "log", st.artefacts.get( 0 ).kind );
  }

  @Test
  public void theIdsAreReadOnlyFromTheCaveAndTheSurveyAndOddValuesAreTolerated()
  {
    // the draft contract gave the ids at the top of the opening answer; the server sends them only in "cave" and "caveSurvey"
    SilexGisStatus st = SilexGisStatus.parse( new JSONObject(
      "{\"id\":\"u2\",\"status\":\"receiving\",\"caveId\":\"c9\",\"caveCreated\":true,\"caveSurveyId\":\"s9\",\"progress\":140,"
      + "\"publicLinkRefusal\":{\"code\":\"survey_upload.public_link_protected\"},\"artefacts\":[null,{\"kind\":\"lox\"}]}" ) );
    assertNull( st.caveId );
    assertNull( st.caveSurveyId );
    assertEquals( 100, st.progress );
    assertFalse( st.isFinished() );
    assertEquals( "survey_upload.public_link_protected", st.publicLinkRefusal );
    assertEquals( 1, st.artefacts.size() );
    assertEquals( "the kind names an artefact without a name", "lox", st.artefacts.get( 0 ).name );
    assertEquals( "", SilexGisStatus.parse( null ).status );
    assertTrue( SilexGisStatus.parse( new JSONObject( "{\"status\":\"abandoned\"}" ) ).isFinished() );
  }
}
