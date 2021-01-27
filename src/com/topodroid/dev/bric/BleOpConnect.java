/* @file BleOpConnect.java
 *
 * @author marco corvi
 * @date jan 2021
 *
 * @brief Bluetooth LE connect operation
 * --------------------------------------------------------
 *  Copyright This software is distributed under GPL-3.0 or later
 *  See the file COPYING.
 * --------------------------------------------------------
 */
package com.topodroid.dev.bric;

import com.topodroid.utils.TDLog; 

import android.content.Context;

import android.bluetooth.BluetoothDevice;

import android.util.Log;

class BleOpConnect extends BleOperation 
{
  BluetoothDevice mDevice;

  BleOpConnect( Context ctx, BleComm pipe, BluetoothDevice device )
  {
    super( ctx, pipe );
    mDevice = device;
  }

  @Override 
  void execute()
  {
    // Log.v("BRIC", "exec connect");
    if ( mPipe == null ) { 
      TDLog.Error("BRIC error: null pipe" );
      return;
    }
    mPipe.connectGatt( mContext, mDevice );
  }
}
