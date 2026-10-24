package android.os;

import java.io.File;

/**
 * JVM stand-in for the one Environment call made while TDPrefKey's key sets are built (the default
 * of CAVE3D_TEXTURE_ROOT). The mockable android.jar answers null, which fails the class initialiser.
 * Test classes come first on the unit-test classpath, so this class replaces the stub there.
 */
public class Environment
{
  public static File getExternalStorageDirectory() { return new File( "/storage/emulated/0" ); }
}
