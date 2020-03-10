/** @file TdmConfigActivity.java
 *
 * @author marco corvi
 * @date may 2012
 *
 * @brief TopoDroid Manager interface activity for a tdconfig file
 * --------------------------------------------------------
 *  Copyright This software is distributed under GPL-3.0 or later
 *  See the file COPYING.
 * --------------------------------------------------------
 */
package com.topodroid.DistoX;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

import android.widget.TextView;
import android.widget.ListView;
import android.app.Dialog;
import android.widget.Button;
import android.widget.ArrayAdapter;

import android.view.View;
// import android.view.ViewGroup.LayoutParams;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.os.Bundle;
import android.app.Activity;

import android.content.res.Resources;

import android.util.Log;

public class TdmConfigActivity extends Activity
                              implements OnClickListener
                              , OnItemClickListener
                              , IExporter
{
  int mNrButton1 = 5;
  private static int izons[] = { 
    R.drawable.iz_add,
    R.drawable.iz_drop,
    R.drawable.iz_view,
    R.drawable.iz_equates,
    R.drawable.iz_3d
  };
  private static final int[] help_icons = {
    R.string.help_add_surveys,
    R.string.help_drop_surveys,
    R.string.help_view_surveys,
    R.string.help_view_equates,
    R.string.help_3d
  };

  boolean onMenu;
  int mNrMenus   = 4;
  private static int menus[] = { 
    R.string.menu_close,
    R.string.menu_export,
    R.string.menu_delete,
    R.string.menu_help
  };
  private static final int[] help_menus = {
    R.string.help_close,
    R.string.help_export_config,
    R.string.help_delete_config,
    R.string.help_help
  };
  private static final int HELP_PAGE = R.string.TdmConfigWindow;

  TdmInputAdapter mTdmInputAdapter;
  // TdManagerApp mApp;
  DataHelper mAppData = TopoDroidApp.mData;

  private static String[] mExportTypes = { "Therion", "Survex" };

  MyHorizontalListView mListView;
  MyHorizontalButtonView mButtonView1;

  ListView mList;
  Button   mImage;
  ListView mMenu;
  ArrayAdapter<String> mMenuAdapter;
  Button[] mButton1;
  private int mButtonSize;

  @Override
  public void onCreate( Bundle savedInstanceState )
  {
    super.onCreate( savedInstanceState );

    // mApp = (TdManagerApp) getApplication();

    TopoDroidApp.mTdmConfig = null;
    Bundle extras = getIntent().getExtras();
    if ( extras != null ) {
      String path = extras.getString( TDRequest.TDCONFIG_PATH );
      if ( path != null ) {
        TopoDroidApp.mTdmConfig = new TdmConfig( path );
        if ( TopoDroidApp.mTdmConfig != null ) {
          TopoDroidApp.mTdmConfig.readTdmConfig();
          setTitle( String.format( getResources().getString(R.string.project),  TopoDroidApp.mTdmConfig.toString() ) );
        } else {
          TDToast.make( R.string.no_file );
        }
      } else {
        // Log.v("DistoX-TdManager", "TdmConfig activity missing TdmConfig path");
        TDToast.make( R.string.no_path );
      }
    }
    if ( TopoDroidApp.mTdmConfig == null ) {
      doFinish( TDRequest.RESULT_TDCONFIG_NONE );
    } else {
      setContentView(R.layout.tdconfig_activity);
      // getWindow().setLayout( LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT );

      mList = (ListView) findViewById(R.id.th_list);
      // mList.setOnItemClickListener( this );
      mList.setDividerHeight( 2 );

      mListView = (MyHorizontalListView) findViewById(R.id.listview);
      resetButtonBar();

      mImage = (Button) findViewById( R.id.handle );
      mImage.setOnClickListener( this );
      TDandroid.setButtonBackground( mImage, MyButton.getButtonBackground( (TopoDroidApp)getApplication(), getResources(), R.drawable.iz_menu ) );

      mMenu = (ListView) findViewById( R.id.menu );
      mMenuAdapter = null;
      setMenuAdapter( getResources() );
      closeMenu();
      mMenu.setOnItemClickListener( this );

      updateList();
    }
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    // Log.v("DistoX-TdManager", "TdmConfig activity on pause");
    if ( TopoDroidApp.mTdmConfig != null ) TopoDroidApp.mTdmConfig.writeTdmConfig( false );
  }

  boolean hasSource( String name ) 
  {
    return TopoDroidApp.mTdmConfig.hasInput( name );
  }

  /** update surveys list
   */
  void updateList()
  {
    if ( TopoDroidApp.mTdmConfig != null ) {
      Log.v("DistoX-TdManager", "TdmConfig update list input nr. " + TopoDroidApp.mTdmConfig.mInputs.size() );
      mTdmInputAdapter = new TdmInputAdapter( this, R.layout.row, TopoDroidApp.mTdmConfig.mInputs );
      mList.setAdapter( mTdmInputAdapter );
      mList.invalidate();
    } else {
      TDToast.make( R.string.no_tdconfig );
    }
  }

  
  // -------------------------------------------------

  private void resetButtonBar()
  {
    if ( mNrButton1 > 0 ) {
      mButtonSize = TopoDroidApp.setListViewHeight( this, mListView );
      // MyButton.resetCache( size );
      // int size = TopoDroidApp.getScaledSize( this );
      // LinearLayout layout = (LinearLayout) findViewById( R.id.list_layout );
      // layout.setMinimumHeight( size + 40 );
      // LayoutParams lp = new LayoutParams( LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT );
      // lp.setMargins( 10, 10, 10, 10 );
      // lp.width  = size;
      // lp.height = size;

      // FIXME TDMANAGER
      mButton1 = new Button[mNrButton1];

      for (int k=0; k<mNrButton1; ++k ) {
        mButton1[k] = MyButton.getButton( this, this, izons[k] );
        // layout.addView( mButton1[k], lp );
      }

      mButtonView1 = new MyHorizontalButtonView( mButton1 );
      mListView.setAdapter( mButtonView1.mAdapter );
    }
  }

  private void setMenuAdapter( Resources res )
  {
    mMenuAdapter = new ArrayAdapter<String>( this, R.layout.menu );
    for ( int k=0; k<mNrMenus; ++k ) {
      mMenuAdapter.add( res.getString( menus[k] ) );  
    }
    mMenu.setAdapter( mMenuAdapter );
    mMenu.invalidate();
  }

  private void closeMenu()
  {
    mMenu.setVisibility( View.GONE );
    onMenu = false;
  }

  private void handleMenu( int pos ) 
  {
    closeMenu();
    int p = 0;
    if ( p++ == pos ) {        // CLOSE
      onBackPressed();
    } else if ( p++ == pos ) {  // EXPORT
      if ( TopoDroidApp.mTdmConfig != null ) {
        new ExportDialog( this, this, mExportTypes, R.string.title_export ).show();
      }
    } else if ( p++ == pos ) { // DELETE
      askDelete();
    } else if ( p++ == pos ) { // HELP
      new HelpDialog(this, izons, menus, help_icons, help_menus, mNrButton1, help_menus.length, getResources().getString( HELP_PAGE ) ).show();
    }
  }

  // ------------------------ DISPLAY -----------------------------
  private void startTdmSurveysActivity()
  {
    TdmSurvey mySurvey = new TdmSurvey( "." );

    for ( TdmInput input : TopoDroidApp.mTdmConfig.mInputs ) {
      if ( input.isChecked() ) {
        input.loadSurveyData ( mAppData );
        mySurvey.addSurvey( input );
        // Log.v("DistoX-TdManager", "parse file " + input.getSurveyName() );
        // TdParser parser = new TdParser( mAppData, input.getSurveyName(), mySurvey );
      }
    }
    if ( mySurvey.mSurveys.size() == 0 ) {
      TDToast.make( R.string.no_surveys );
      return;
    }
    // list of display surveys
    TopoDroidApp.mTdmConfig.populateViewSurveys( mySurvey.mSurveys );

    // TODO start drawing activity with reduced surveys
    Intent intent = new Intent( this, TdmViewActivity.class );
    startActivity( intent );
  }

  // ------------------------ ADD ------------------------------
  // called by TdmSourcesDialog with a list of sources filenames
  //
  void addSources( List<String> surveynames )
  {
    for ( String name : surveynames ) {
      // Log.v("DistoX-TdManager", "add  source " + name );
      mTdmInputAdapter.add( new TdmInput( name ) ) ;
    }
    updateList();
  }

  // ------------------------ DELETE ------------------------------
  private void askDelete()
  {
    TopoDroidAlertDialog.makeAlert( this, getResources(), 
                             getResources().getString( R.string.ask_delete_tdconfig ),
      new DialogInterface.OnClickListener() {
        @Override
        public void onClick( DialogInterface dialog, int btn ) {
          doDelete();
        }
      }
    );
  }

  void doDelete()
  {
    // if ( ! TdManagerApp.deleteTdmConfigFile( TopoDroidApp.mTdmConfig.getFilepath() ) ) { 
    //   TDToast.make( "delete FAILED" );
    // } else {
      doFinish( TDRequest.RESULT_TDCONFIG_DELETE );
    // }
  }

  void doFinish( int result )
  {
    Intent intent = new Intent();
    if ( TopoDroidApp.mTdmConfig != null ) {
      intent.putExtra( TDRequest.TDCONFIG_PATH, TopoDroidApp.mTdmConfig.getFilepath() );
    } else {
      intent.putExtra( TDRequest.TDCONFIG_PATH, "no_path" );
    }
    setResult( result, intent );
    finish();
  }
  // ---------------------- DROP SURVEYS ----------------------------
  void dropSurveys()
  {
    TopoDroidAlertDialog.makeAlert( this, getResources(), getResources().getString( R.string.title_drop ), 
      new DialogInterface.OnClickListener() {
	@Override
	public void onClick( DialogInterface dialog, int btn ) {
          ArrayList< TdmInput > inputs = new ArrayList< TdmInput >();
          final Iterator it = TopoDroidApp.mTdmConfig.mInputs.iterator();
          while ( it.hasNext() ) {
            TdmInput input = (TdmInput) it.next();
            if ( ! input.isChecked() ) {
              inputs.add( input );
            } else {
              String survey = input.getSurveyName();
              // Log.v("DistoX-TdManager", "drop survey >" + survey + "<" );
              TopoDroidApp.mTdmConfig.dropEquates( survey );
            }
          }
          TopoDroidApp.mTdmConfig.mInputs = inputs;
          updateList();
	} 
    } );
  }

  // ---------------------- SAVE -------------------------------------

  @Override
  public void onBackPressed()
  {
    // Log.v("DistoX-TdManager", "TdmConfig activity back pressed");
    // if ( TopoDroidApp.mTdmConfig != null ) TopoDroidApp.mTdmConfig.writeTdmConfig( false );
    doFinish( TDRequest.RESULT_TDCONFIG_OK );
  }

  @Override
  public void onClick(View view)
  { 
    if ( onMenu ) {
      closeMenu();
      return;
    }
    Button b0 = (Button)view;

    if ( b0 == mImage ) {
      if ( mMenu.getVisibility() == View.VISIBLE ) {
        mMenu.setVisibility( View.GONE );
        onMenu = false;
      } else {
        mMenu.setVisibility( View.VISIBLE );
        onMenu = true;
      }
      return;
    }

    int k1 = 0;
    if ( k1 < mNrButton1 && b0 == mButton1[k1++] ) {  // ADD
      (new TdmSourcesDialog(this, this)).show();
    } else if ( k1 < mNrButton1 && b0 == mButton1[k1++] ) {  // DROP
      boolean drop = false;
      final Iterator it = TopoDroidApp.mTdmConfig.mInputs.iterator();
      while ( it.hasNext() ) {
        TdmInput input = (TdmInput) it.next();
        if ( input.isChecked() ) { drop = true; break; }
      }
      if ( drop ) {
        dropSurveys();
      } else {
        TDToast.make( R.string.no_survey );
      }
    } else if ( k1 < mNrButton1 && b0 == mButton1[k1++] ) {  // VIEW
      startTdmSurveysActivity();
    } else if ( k1 < mNrButton1 && b0 == mButton1[k1++] ) {  // EQUATES
      (new TdmEquatesDialog( this, TopoDroidApp.mTdmConfig, null )).show();
    } else if ( k1 < mNrButton1 && b0 == mButton1[k1++] ) {  // 3D
      try {
        // Log.v("DistoX-TdManager", "Cave3D of " + TopoDroidApp.mTdmConfig.getFilepath() );
        Intent intent = new Intent( "Cave3D.intent.action.Launch" );
        intent.putExtra( "INPUT_FILE", TopoDroidApp.mTdmConfig.getFilepath() );
        startActivity( intent );
      } catch ( ActivityNotFoundException e ) {
        TDToast.make( R.string.no_cave3d );
      }
    }
  }

  public void doExport( String type /* , boolean overwrite */ )
  {
    boolean overwrite = true;
    String filepath = null;
    if ( type.equals("Therion") ) {
       filepath = TopoDroidApp.mTdmConfig.exportTherion( overwrite );
    } else if ( type.equals("Survex") ) {
       filepath = TopoDroidApp.mTdmConfig.exportSurvex( overwrite );
    }
    if ( filepath != null ) {
      TDToast.make( String.format( getResources().getString(R.string.exported), filepath ) );
    } else {
      TDToast.make( R.string.export_failed );
    }
  }


  @Override
  public void onItemClick( AdapterView<?> parent, View view, int pos, long id )
  {
    // CharSequence item = ((TextView) view).getText();
    if ( mMenu == (ListView)parent ) {
      handleMenu( pos );
      return;
    }
    if ( onMenu ) {
      closeMenu();
      return;
    }
  }

}
