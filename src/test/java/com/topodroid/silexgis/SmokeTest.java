package com.topodroid.silexgis;

import static org.junit.Assert.assertEquals;

import com.topodroid.util.TDMath;
import org.junit.Test;

/** Proves the JVM test set compiles against the app's plain-Java classes and runs. */
public class SmokeTest
{
  @Test
  public void anglesWrapIntoZeroTo360()
  {
    assertEquals( 10, TDMath.in360( 370 ) );
    assertEquals( 350, TDMath.in360( -10 ) );
  }

  @Test
  public void averageOfAnglesAcrossNorthStaysNorth()
  {
    assertEquals( 0.0f, TDMath.averageDegrees( 350f, 10f ), 1e-4f );
  }
}
