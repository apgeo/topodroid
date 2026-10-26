package com.topodroid.TDX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * What a SilexGIS upload remembers: the last switch combination (the settings only the first time)
 * and, per TopoDroid survey, where it was uploaded. Survey names and ids are invented.
 */
public class SilexGisStoreTest
{
  private final Map< String, String > prefs = new HashMap<>();
  private final SilexGisStore store = new SilexGisStore( new SilexGisStore.Prefs() {
    @Override public String get( String key ) { return prefs.get( key ); }
    @Override public void put( String key, String value ) { if ( value == null ) prefs.remove( key ); else prefs.put( key, value ); }
  } );

  private static SilexGisUpload settings()
  {
    SilexGisUpload s = new SilexGisUpload();
    s.withTh = true; s.compile = true; s.outputs = "lox,3d"; s.visibility = "private"; s.publicLink = false;
    return s;
  }

  @Test
  public void theFirstUploadTakesTheSettingsAndAnAutomaticTarget()
  {
    SilexGisUpload u = store.load( "pestera_inventata", settings() );
    assertEquals( SilexGisUpload.TARGET_AUTO, u.target );
    assertEquals( "lox,3d", u.outputs );
    assertEquals( "private", u.visibility );
    assertFalse( u.publicLink );
    assertNull( store.loadTarget( "pestera_inventata" ) );
  }

  @Test
  public void theNextUploadTakesTheLastCombinationNotTheSettings()
  {
    SilexGisUpload u = store.load( "a", settings() );
    u.compile = false; u.withSvx = true; u.outputs = "lox"; u.visibility = "public"; u.publicLink = true;
    store.save( "a", u );
    SilexGisUpload v = store.load( "b", settings() ); // the switches are the same for every survey
    assertFalse( v.compile );
    assertTrue( v.withSvx );
    assertEquals( "lox", v.outputs );
    assertEquals( "public", v.visibility );
    assertTrue( v.publicLink );
    assertEquals( "another survey keeps its own target", SilexGisUpload.TARGET_AUTO, v.target );
  }

  @Test
  public void eachSurveyRemembersItsOwnTarget()
  {
    SilexGisUpload a = store.load( "a", settings() );
    a.setCave( "c1", "Pestera Inventata" );
    store.save( "a", a );
    SilexGisUpload b = store.load( "b", settings() );
    b.setNewCave( "Pestera Noua", "cavingGroup", false );
    store.save( "b", b );
    assertEquals( SilexGisUpload.TARGET_CAVE, store.load( "a", settings() ).target );
    assertEquals( "c1", store.load( "a", settings() ).caveId );
    assertEquals( SilexGisUpload.TARGET_NEW_CAVE, store.load( "b", settings() ).target );
    b.setAuto();
    store.save( "b", b );
    assertFalse( "an automatic target leaves nothing behind", prefs.containsKey( SilexGisStore.targetKey( "b" ) ) );
  }

  @Test
  public void theSurveyTheServerAnsweredBecomesTheTargetOfTheNextUpload()
  {
    SilexGisUpload a = store.load( "a", settings() );
    a.setNewCave( "Pestera Noua", "public", false );
    store.save( "a", a );
    store.saveAnswer( "a", "s7", "pestera noua", "c7", "Pestera Noua" );
    SilexGisUpload next = store.load( "a", settings() );
    assertEquals( "the next upload is a new version, not a second new cave", SilexGisUpload.TARGET_CAVE_SURVEY, next.target );
    assertEquals( "s7", next.caveSurveyId );
    assertEquals( "c7", next.caveId );
    assertEquals( "Pestera Noua", next.caveName );
    assertNull( next.newCaveName );
    SilexGisUpload remembered = store.loadTarget( "a" );
    assertEquals( "s7", remembered.caveSurveyId );
    store.saveAnswer( "a", null, "x", null, null );
    assertEquals( "an answer without a survey id changes nothing", "s7", store.loadTarget( "a" ).caveSurveyId );
  }

  @Test
  public void theAccountAudienceIsTheDefaultUntilAnAudienceIsChosen()
  {
    assertEquals( "nothing known: a link for anyone", "public", store.load( "a", new SilexGisUpload() ).linkAudience );
    store.saveAccountAudience( "organization" );
    assertEquals( "organization", store.load( "a", new SilexGisUpload() ).linkAudience );
    SilexGisUpload u = store.load( "a", settings() );
    assertEquals( "organization", u.linkAudience );
    u.setLinkChoice( SilexGisUpload.LINK_PUBLIC );
    store.save( "a", u );
    assertEquals( "the last choice wins over the account", "public", store.load( "b", settings() ).linkAudience );
    store.saveAccountAudience( "world" );
    store.saveAccountAudience( null );
    assertEquals( "an unknown audience is not remembered", "organization", prefs.get( SilexGisStore.KEY_AUDIENCE ) );
  }

  @Test
  public void anOlderRememberedCombinationTakesTheAccountAudience()
  {
    prefs.put( SilexGisStore.KEY_SWITCHES, "{\"publicLink\":true,\"compile\":false}" ); // remembered before the audience existed
    store.saveAccountAudience( "organization" );
    SilexGisUpload u = store.load( "a", settings() );
    assertFalse( u.compile );
    assertEquals( SilexGisUpload.LINK_ORGANIZATION, u.linkChoice() );
  }

  @Test
  public void aDamagedRememberedValueIsIgnored()
  {
    prefs.put( SilexGisStore.KEY_SWITCHES, "{not json" );
    prefs.put( SilexGisStore.targetKey( "a" ), "[]" );
    SilexGisUpload u = store.load( "a", settings() );
    assertEquals( "lox,3d", u.outputs );
    assertEquals( SilexGisUpload.TARGET_AUTO, u.target );
    assertNull( store.loadTarget( "a" ) );
  }
}
