/* @file MemoryCavwayTask.java
 *
 * @author marco corvi
 * @date apr 2016
 *
 * @brief TopoDroid DistoX memory read task
 * --------------------------------------------------------
 *  Copyright This software is distributed under GPL-3.0 or later
 *  See the file COPYING.
 * --------------------------------------------------------
 */
package com.topodroid.dev.cavway;

import com.topodroid.dev.Device;

// import com.topodroid.utils.TDLog;
// import com.topodroid.utils.TDFile;

import com.topodroid.packetX.CavwayData;
// import com.topodroid.dev.cavway.CavwayMemoryDialog;
// import com.topodroid.dev.Device;
import com.topodroid.TDX.TopoDroidApp;
import com.topodroid.TDX.TDPath;
import com.topodroid.TDX.TDToast;
import com.topodroid.TDX.R;
import com.topodroid.utils.TDLog;

import java.lang.ref.WeakReference;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;

// import java.util.List;
import java.util.ArrayList;

// import android.app.Activity;
import android.os.AsyncTask;
// import android.content.Context;

public class MemoryCavwayTask extends AsyncTask<Void, Integer, Integer>
{
  private final WeakReference<TopoDroidApp> mApp;
  private WeakReference< CavwayMemoryDialog > mDialog;
  private int mType; // Cavway type
  private String mAddress;
  private int mHT; // the number of data to read
  private String mDumpfile = null;
  private ArrayList< CavwayData > mMemory;

  public MemoryCavwayTask( TopoDroidApp app, CavwayMemoryDialog dialog, int type, String address, int ht, String dumpfile )
  {
    mApp      = new WeakReference<TopoDroidApp>( app );
    mDialog   = new WeakReference< CavwayMemoryDialog >( dialog );
    mType     = type;
    mAddress  = address;
    mHT       = ht;
    mDumpfile = dumpfile;
    mMemory   = new ArrayList<>();
  }

  @Override
  protected Integer doInBackground(Void... v)
  {
    int res = 0;
    if ( mApp.get() != null ) {
      if ( mType == Device.DISTO_CAVWAYX1 ) {
        res = mApp.get().readCavwayX1Memory( mAddress, mHT, mMemory, mDialog.get() );
      }
    }
    if ( res > 0 ) {
      writeMemoryDumpToFile( mDumpfile, mMemory );
    }
    return res;
  }

  // @Override
  // protected void onProgressUpdate(Integer... progress)
  // {
  // }

  @Override
  protected void onPostExecute( Integer result )
  {
    if ( result > 0 && mDialog.get() != null ) {
      mDialog.get().updateList( mMemory );
    } else {
      TDToast.makeBad( R.string.read_failed );
    }
  }

  private void writeMemoryDumpToFile( String dumpfile, ArrayList< CavwayData > memory )
  {
    if ( dumpfile == null ) return;
    dumpfile = dumpfile.trim();
    if ( dumpfile.length() == 0 ) return;
    try { 
      // TDLog.Log( TDLog.LOG_IO, "dump memory to file " + dumpfile );
      // String dump_path = TDPath.getDumpFile( dumpfile );
      // TDPath.checkPath( dump_path );
      // FileWriter fw = new FileWriter( dump_path );
      File file = TDPath.getDumpFile( dumpfile );
      FileWriter fw = new FileWriter( file );
      PrintWriter pw = new PrintWriter( fw );
      for ( CavwayData m : memory ) {
        m.printHexString( pw );
        pw.format(" " + m.toString() + "\n");
      }
      fw.flush();
      fw.close();
    } catch ( IOException e ) {
      TDLog.e("IO error " + e.getMessage() );
    }
  }
}
