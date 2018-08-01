/* @file TDInstance.java
 *
 * @author marco corvi
 * @date aug 2018
 *
 * @brief TopoDroid instance data
 * --------------------------------------------------------
 *  Copyright This software is distributed under GPL-3.0 or later
 *  See the file COPYING.
 * --------------------------------------------------------
 */
package com.topodroid.DistoX;

import android.os.Bundle;

// static class (singleton) with instance data
class TDInstance
{
  static String cwd;  // current work directory
  static String cbd;  // current base directory

  static long sid   = -1;   // id of the current survey
  static long cid   = -1;   // id of the current calib
  static String survey;   // current survey name
  static String calib;    // current calib name
  static long secondLastShotId = 0L;

  static boolean xsections = false; // current value of mSharedSections
  static Device  device = null;

  static int distoType() { return (device == null)? 0 : device.mType; }
  static String distoAddress() { return (device == null)? null : device.mAddress; }
  static boolean isDeviceAddress( String addr ) { return device != null && device.mAddress.equals( addr ); }
  static boolean isDeviceZeroAddress( ) { return ( device == null || device.mAddress.equals( Device.ZERO_ADDRESS ) ); }

  static Bundle toBundle()
  {
    Bundle b = new Bundle();
    b.putString( "TOPODROID_CWD", cwd );
    b.putString( "TOPODROID_CBD", cbd );
    b.putLong( "TOPODROID_SID", sid );
    b.putLong( "TOPODROID_CID", cid );
    b.putString( "TOPODROID_SURVEY", survey );
    b.putString( "TOPODROID_CALIB",  calib  );
    b.putLong( "TOPODROID_SECOND_LAST_SHOT_ID", secondLastShotId );
    b.putBoolean( "TOPODROID_XSECTIONS", xsections );
    b.putString( "TOPODROID_DEVICE", ( (device == null)? "" : device.mAddress)  );
    return b;
  }

  static void fromBundle( Bundle b )
  {
    cwd = b.getString( "TOPODROID_CWD" );
    cbd = b.getString( "TOPODROID_CBD" );
    sid = b.getLong( "TOPODROID_SID" );
    cid = b.getLong( "TOPODROID_CID" );
    survey = b.getString( "TOPODROID_SURVEY" );
    calib  = b.getString( "TOPODROID_CALIB"  );
    secondLastShotId = b.getLong( "TOPODROID_SECOND_LAST_SHOT_ID" );
    xsections = b.getBoolean( "TOPODROID_XSECTIONS" );
    String addr = b.getString( "TOPODROID_DEVICE" );
    if ( addr == null || addr.length() == 0 ) {
      device = null;
    } else {
      // device = TopoDroidApp.setDevice( addr ); // FIXME_DEVICE_STATIC
    }
  }
}
