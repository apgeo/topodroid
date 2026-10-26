package com.topodroid.TDX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The link to the results of a SilexGIS upload as the phone handles it: the choice sent with the opening,
 * the link read from the status, and the requests that make a new link (also for a protected cave, only when
 * asked for on purpose) and withdraw it. The answers are written to the shapes of the owner's decisions
 * (make: POST, withdraw: DELETE /api/v1/survey-uploads/{id}/public-link, body {audience, allowProtected});
 * they are to be checked against the server's recordings once it records them. Every value is invented.
 */
public class SilexGisLinkTest
{
  private static final String KEY    = "sgx_TestKeyAAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIII";
  private static final String UPLOAD = "0190a000-0000-7000-8000-0000000000a1";
  private static final String LINK   = "/api/v1/survey-uploads/" + UPLOAD + "/public-link";
  private static final String STATUS = "/api/v1/survey-uploads/" + UPLOAD;

  private FakeSilexGis server;
  private SilexGisClient client;

  @Before
  public void start() throws IOException
  {
    server = new FakeSilexGis();
    client = new SilexGisClient( server.base(), KEY );
  }

  @After
  public void stop() { server.stop(); }

  private static SilexGisStatus status( String link, String refusal )
  {
    JSONObject json = new JSONObject();
    json.put( "id", UPLOAD ).put( "status", "succeeded" );
    json.put( "publicLink", ( link == null )? JSONObject.NULL : new JSONObject( link ) );
    json.put( "publicLinkRefusal", ( refusal == null )? JSONObject.NULL : refusal );
    return SilexGisStatus.parse( json );
  }

  // ---------------------------------------------------------------------
  // the choice of the export dialog

  @Test
  public void theOpeningCarriesTheLinkChoice()
  {
    SilexGisUpload u = new SilexGisUpload();
    assertEquals( "the default: a link for anyone", SilexGisUpload.LINK_PUBLIC, u.linkChoice() );
    JSONObject link = u.openRequest( "t", "n", null, "v", null, null, false ).getJSONObject( "publicLink" );
    assertTrue( link.getBoolean( "wanted" ) );
    assertEquals( "public", link.getString( "audience" ) );
    assertFalse( "a protected cave is never linked from the opening", link.getBoolean( "allowProtected" ) );

    u.setLinkChoice( SilexGisUpload.LINK_ORGANIZATION );
    link = u.openRequest( "t", "n", null, "v", null, null, false ).getJSONObject( "publicLink" );
    assertTrue( link.getBoolean( "wanted" ) );
    assertEquals( "organization", link.getString( "audience" ) );
    assertFalse( link.getBoolean( "allowProtected" ) );

    u.setLinkChoice( SilexGisUpload.LINK_NONE );
    assertEquals( SilexGisUpload.LINK_NONE, u.linkChoice() );
    link = u.openRequest( "t", "n", null, "v", null, null, false ).getJSONObject( "publicLink" );
    assertFalse( "no link for this upload, whatever the account says", link.getBoolean( "wanted" ) );
    assertFalse( link.getBoolean( "allowProtected" ) );
    assertEquals( "no link keeps the audience for the next time a link is chosen", "organization", u.linkAudience );
  }

  @Test
  public void theLinkChoiceIsRememberedWithTheOtherSwitches()
  {
    SilexGisUpload u = new SilexGisUpload();
    u.setLinkChoice( SilexGisUpload.LINK_ORGANIZATION );
    SilexGisUpload v = new SilexGisUpload();
    v.switchesFromJson( new JSONObject( u.switchesToJson().toString() ) );
    assertEquals( SilexGisUpload.LINK_ORGANIZATION, v.linkChoice() );
    u.setLinkChoice( SilexGisUpload.LINK_NONE );
    v.switchesFromJson( new JSONObject( u.switchesToJson().toString() ) );
    assertEquals( SilexGisUpload.LINK_NONE, v.linkChoice() );
    v.switchesFromJson( new JSONObject( "{\"publicLink\":true,\"linkAudience\":\"everybody\"}" ) );
    assertEquals( "an unknown audience is not taken", "organization", v.linkAudience );
  }

  @Test
  public void theAccountAudienceIsReadFromTheCapabilities()
  {
    assertEquals( "organization", SilexGisUploader.accountLinkAudience( new JSONObject( "{\"publicLinks\":{\"defaultAudience\":\"organization\"}}" ) ) );
    assertEquals( "public", SilexGisUploader.accountLinkAudience( new JSONObject( "{\"publicLinks\":{\"defaultAudience\":\"public\"}}" ) ) );
    assertNull( SilexGisUploader.accountLinkAudience( new JSONObject( "{\"publicLinks\":{\"defaultAudience\":\"world\"}}" ) ) );
    assertNull( "a server that does not say", SilexGisUploader.accountLinkAudience( new JSONObject( "{\"pollIntervalSeconds\":3}" ) ) );
    assertNull( SilexGisUploader.accountLinkAudience( null ) );
  }

  // ---------------------------------------------------------------------
  // the link of the status

  @Test
  public void theLinkAndItsAudienceAreReadFromTheStatus()
  {
    SilexGisLink l = SilexGisLink.of( status( "{\"url\":\"/p/survey/t1\",\"createdAt\":\"2026-10-26T10:00:00Z\",\"audience\":\"organization\"}", null ) );
    assertTrue( l.hasLink() );
    assertEquals( "/p/survey/t1", l.url );
    assertTrue( "an organization link asks to sign in", l.isOrganization() );
    assertFalse( l.isProtected() );

    l = SilexGisLink.of( status( "{\"url\":\"/p/survey/t2\",\"createdAt\":\"2026-10-26T10:00:00Z\"}", null ) );
    assertTrue( l.hasLink() );
    assertFalse( "a link without audience is one for anyone", l.isOrganization() );

    l = SilexGisLink.of( status( null, "protected-cave" ) );
    assertFalse( l.hasLink() );
    assertTrue( l.isProtected() );

    l = SilexGisLink.of( status( null, "not-wanted" ) );
    assertTrue( "the upload asked for no link", l.isNotWanted() );
    assertFalse( l.isProtected() );

    l = SilexGisLink.of( status( null, "account-off" ) );
    assertFalse( l.isNotWanted() );
    assertFalse( l.isProtected() );
    assertEquals( "account-off", l.refusal );

    l = SilexGisLink.of( null );
    assertFalse( l.hasLink() );
    assertFalse( l.isProtected() );
  }

  // ---------------------------------------------------------------------
  // make and withdraw

  @Test
  public void aNewLinkIsMadeForTheChosenAudience()
  {
    server.on( "POST", LINK, 201, "{\"url\":\"/p/survey/new1\",\"createdAt\":\"2026-10-26T10:00:00Z\",\"audience\":\"organization\"}" );
    SilexGisLink l = SilexGisLink.of( status( "{\"url\":\"/p/survey/old\"}", null ) );
    assertEquals( SilexGisLink.MADE, l.make( client, UPLOAD, "organization", false ) );
    assertEquals( "/p/survey/new1", l.url );
    assertTrue( l.isOrganization() );
    assertNull( l.refusal );
    List< FakeSilexGis.Request > sent = server.of( "POST", LINK );
    assertEquals( 1, sent.size() );
    JSONObject body = new JSONObject( sent.get( 0 ).text() );
    assertEquals( "organization", body.getString( "audience" ) );
    assertFalse( body.getBoolean( "allowProtected" ) );
    assertEquals( "the access key opens the route", "Bearer " + KEY, sent.get( 0 ).headers.get( "authorization" ) );
  }

  @Test
  public void anAnswerWithoutTheAudienceKeepsTheOneAskedFor()
  {
    server.on( "POST", LINK, 201, "{\"url\":\"/p/survey/new2\",\"createdAt\":\"2026-10-26T10:00:00Z\"}" );
    SilexGisLink l = new SilexGisLink();
    assertEquals( SilexGisLink.MADE, l.make( client, UPLOAD, "public", false ) );
    assertEquals( "/p/survey/new2", l.url );
    assertEquals( "public", l.audience );
    assertEquals( "public", new JSONObject( server.of( "POST", LINK ).get( 0 ).text() ).getString( "audience" ) );
  }

  @Test
  public void anAnswerWithoutTheLinkIsCompletedByTheStatus()
  {
    server.on( "POST", LINK, new FakeSilexGis.Answer( 204, null, new byte[0] ) )
          .on( "GET", STATUS, 200, "{\"id\":\"" + UPLOAD + "\",\"status\":\"succeeded\",\"publicLink\":{\"url\":\"/p/survey/new3\",\"audience\":\"organization\"},\"publicLinkRefusal\":null}" );
    SilexGisLink l = new SilexGisLink();
    assertEquals( SilexGisLink.MADE, l.make( client, UPLOAD, "organization", false ) );
    assertEquals( "/p/survey/new3", l.url );
    assertTrue( l.isOrganization() );
    assertEquals( Arrays.asList( "POST " + LINK, "GET " + STATUS ), server.trail() );
  }

  @Test
  public void aProtectedCaveGetsALinkOnlyWhenAskedForOnPurpose()
  {
    server.on( "POST", LINK, 409, "{\"title\":\"Conflict\",\"status\":409,\"code\":\"survey_upload.public_link_refused_protected\",\"detail\":\"The cave's position is protected.\"}" )
          .on( "POST", LINK, 201, "{\"url\":\"/p/survey/ack1\",\"createdAt\":\"2026-10-26T10:00:00Z\",\"audience\":\"public\"}" );
    SilexGisLink l = new SilexGisLink();
    assertEquals( SilexGisLink.PROTECTED, l.make( client, UPLOAD, "public", false ) );
    assertTrue( "the dialog explains and offers the link anyway", l.isProtected() );
    assertFalse( l.hasLink() );
    assertEquals( SilexGisLink.MADE, l.make( client, UPLOAD, "public", true ) );
    assertEquals( "/p/survey/ack1", l.url );
    assertFalse( l.isProtected() );
    List< FakeSilexGis.Request > sent = server.of( "POST", LINK );
    assertEquals( 2, sent.size() );
    assertFalse( new JSONObject( sent.get( 0 ).text() ).getBoolean( "allowProtected" ) );
    assertTrue( "the second request carries the acknowledgement", new JSONObject( sent.get( 1 ).text() ).getBoolean( "allowProtected" ) );
  }

  @Test
  public void aRefusedNewLinkKeepsTheLinkTheUploadHad()
  {
    server.on( "POST", LINK, 409, "{\"title\":\"Conflict\",\"status\":409,\"code\":\"survey_upload.public_link_refused_protected\"}" )
          .on( "POST", LINK, 201, "{\"url\":\"/p/survey/ack2\",\"createdAt\":\"2026-10-26T10:00:00Z\",\"audience\":\"public\"}" );
    SilexGisLink l = SilexGisLink.of( status( "{\"url\":\"/p/survey/kept\",\"audience\":\"organization\"}", null ) );
    assertEquals( SilexGisLink.PROTECTED, l.make( client, UPLOAD, "public", false ) );
    assertTrue( "the server refuses before it replaces: the link is still there", l.hasLink() );
    assertEquals( "/p/survey/kept", l.url );
    assertTrue( "the dialog offers the link anyway", l.isProtected() );
    assertEquals( SilexGisLink.MADE, l.make( client, UPLOAD, "public", true ) );
    assertEquals( "/p/survey/ack2", l.url );
    assertFalse( l.isProtected() );
  }

  @Test
  public void theLinkIsWithdrawn()
  {
    server.on( "DELETE", LINK, new FakeSilexGis.Answer( 204, null, new byte[0] ) );
    SilexGisLink l = SilexGisLink.of( status( "{\"url\":\"/p/survey/old\",\"audience\":\"organization\"}", null ) );
    assertEquals( SilexGisLink.WITHDRAWN, l.withdraw( client, UPLOAD ) );
    assertFalse( l.hasLink() );
    assertFalse( l.isOrganization() );
    assertEquals( "revoked", l.refusal );
    assertEquals( Arrays.asList( "DELETE " + LINK ), server.trail() );
    assertEquals( "", server.of( "DELETE", LINK ).get( 0 ).text() );
  }

  @Test
  public void aLinkAlreadyGoneIsWithdrawn()
  {
    server.on( "DELETE", LINK, 404, "{\"title\":\"Not Found\",\"status\":404,\"code\":\"survey_upload.public_link_not_found\"}" );
    SilexGisLink l = SilexGisLink.of( status( "{\"url\":\"/p/survey/old\"}", null ) );
    assertEquals( SilexGisLink.WITHDRAWN, l.withdraw( client, UPLOAD ) );
    assertFalse( l.hasLink() );
  }

  @Test
  public void refusalsKeepTheLinkAsItWas()
  {
    server.on( "POST", LINK, 403, "{\"title\":\"Forbidden\",\"status\":403,\"code\":\"survey_upload.public_link_forbidden\",\"detail\":\"Making a public link takes the right to share the cave.\"}" )
          .on( "DELETE", LINK, 403, "{\"title\":\"Forbidden\",\"status\":403,\"code\":\"auth.access_key_not_accepted\"}" );
    SilexGisLink l = SilexGisLink.of( status( "{\"url\":\"/p/survey/old\"}", null ) );
    assertEquals( SilexGisLink.REFUSED, l.make( client, UPLOAD, "public", false ) );
    assertEquals( "survey_upload.public_link_forbidden", l.result.code );
    assertEquals( "/p/survey/old", l.url );
    assertEquals( "a server whose link routes refuse the key", SilexGisLink.REFUSED, l.withdraw( client, UPLOAD ) );
    assertEquals( SilexGisClient.KEY_NOT_ACCEPTED, l.result.kind );
    assertEquals( "/p/survey/old", l.url );
  }

  @Test
  public void aRefusedKeyIsToldApart()
  {
    server.on( "POST", LINK, 401, "{\"title\":\"Unauthorized\",\"status\":401}" )
          .on( "DELETE", LINK, 401, "{\"title\":\"Unauthorized\",\"status\":401}" );
    SilexGisLink l = SilexGisLink.of( status( "{\"url\":\"/p/survey/old\"}", null ) );
    assertEquals( SilexGisLink.KEY_REFUSED, l.make( client, UPLOAD, "public", false ) );
    assertEquals( SilexGisLink.KEY_REFUSED, l.withdraw( client, UPLOAD ) );
    assertEquals( "/p/survey/old", l.url );
  }

  @Test
  public void theMakeRequestNeverSendsAnUnknownAudience()
  {
    JSONObject body = SilexGisLink.makeRequest( "everybody", true );
    assertEquals( "public", body.getString( "audience" ) );
    assertTrue( body.getBoolean( "allowProtected" ) );
    assertEquals( 2, body.length() );
  }
}
