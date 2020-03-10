/** @file TdmSourcesDialog.java
 *
 * @author marco corvi
 * @date nov 2011
 *
 * @brief TopoDroid Manager survey source dialog
 * --------------------------------------------------------
 *  Copyright This sowftare is distributed under GPL-3.0 or later
 *  See the file COPYING.
 * --------------------------------------------------------
 */
package com.topodroid.DistoX;

import java.util.List;
import java.util.ArrayList;
import java.io.File;

import android.app.Dialog;
import android.widget.ListView;
import android.widget.Button;

import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import android.util.Log;

class TdmSourcesDialog extends MyDialog
                       implements OnClickListener
{
  Context mContext;
  TdmSourceAdapter mTdmSourceAdapter;
  TdmConfigActivity mParent;
  ListView mList;
  ArrayList< TdmSource > mSources;

  TdmSourcesDialog( Context context, TdmConfigActivity parent )
  {
    super( context, R.string.TdmSourcesDialog );
    mContext = context;
    mParent = parent;
    mSources = new ArrayList< TdmSource >();
  }

  @Override
  public void onCreate( Bundle savedInstanceState )
  {
    super.onCreate( savedInstanceState );
    initLayout( R.layout.tdsources_dialog, R.string.title_surveys );

    ((Button) findViewById( R.id.ok ) ).setOnClickListener( this );
    ((Button) findViewById( R.id.back ) ).setOnClickListener( this );

    mList = (ListView) findViewById(R.id.list);
    mList.setDividerHeight( 2 );

    updateList();
  }

  void updateList()
  {
    mTdmSourceAdapter = new TdmSourceAdapter( mContext, R.layout.tdsource_adapter, mSources );
    List< String > surveys = mParent.mAppData.selectAllSurveys(); 
    for ( String name : surveys ) {
      if ( ! mParent.hasSource( name ) ) {
        // Log.v("TdManager", "source name " + name + " path " + path );
        mTdmSourceAdapter.addTdmSource( new TdmSource( name ) );
      }
    }
    if ( mTdmSourceAdapter.size() > 0 ) {
      mList.setAdapter( mTdmSourceAdapter );
      // mList.invalidate();
    } else {
      hide();
      TDToast.make( R.string.no_th_file );
      dismiss();
    }
  }

  @Override
  public void onClick( View v )
  {
    if ( v.getId() == R.id.ok ) {
      hide();
      List<String> sources = mTdmSourceAdapter.getCheckedSources();
      mParent.addSources( sources );
    // } else if ( v.getId() == R.id/back ) {
    //   // nothing
    }
    dismiss();
  }

}
