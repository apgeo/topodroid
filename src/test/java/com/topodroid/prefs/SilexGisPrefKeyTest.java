package com.topodroid.prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The SilexGIS access key is a secret: the settings export skips it. The export walks the key sets by
 * index and leaves out the keys that repeatedKey(j, k) names, so these tests pin the index pair and the
 * name together: moving the key set, reordering its rows or dropping the exclusion fails here.
 */
public class SilexGisPrefKeyTest
{
  /** TDPrefKey's initialiser asks TDLevel whether the build is debuggable, which reads the package
   * manager of the application context; there is none on the JVM, so the answer is set beforehand
   */
  @BeforeClass
  public static void notADebugBuild() throws Exception
  {
    Field has = com.topodroid.TDX.TDLevel.class.getDeclaredField( "hasIsDebug" );
    has.setAccessible( true );
    has.setBoolean( null, true );
  }

  @Test
  public void theAccessKeyIsTheOnlySecretAndIsExcludedFromTheExport()
  {
    int secrets = 0;
    for ( int j = 0; j < TDPrefKey.nrKeySets(); ++j ) {
      for ( int k = 0; k < TDPrefKey.nrKeys( j ); ++k ) {
        TDPrefKey kay = TDPrefKey.getKey( j, k );
        if ( kay == null ) continue;
        if ( kay.key.equals( "DISTOX_SILEXGIS_KEY" ) ) {
          assertTrue( "secret at " + j + "," + k, TDPrefKey.isSecret( kay.key ) );
        }
        if ( TDPrefKey.isSecret( kay.key ) ) {
          ++secrets;
          assertTrue( kay.key + " at " + j + "," + k + " would be exported", TDPrefKey.repeatedKey( j, k ) );
        }
      }
    }
    assertEquals( 1, secrets );
  }

  @Test
  public void theExclusionNamesTheAccessKeyRow()
  {
    assertSame( TDPrefKey.mExportSilexgis, TDPrefKey.mKeySet[43] );
    assertEquals( "DISTOX_SILEXGIS_KEY", TDPrefKey.mKeySet[43][1].key );
    assertTrue( TDPrefKey.repeatedKey( 43, 1 ) );
    // the other rows of the page are exported
    assertEquals( "DISTOX_SILEXGIS_URL", TDPrefKey.mKeySet[43][0].key );
    assertFalse( TDPrefKey.repeatedKey( 43, 0 ) );
    assertNotNull( TDPrefKey.mKeySet[43][2] );
    assertFalse( TDPrefKey.isSecret( "DISTOX_SILEXGIS_URL" ) );
  }
}
