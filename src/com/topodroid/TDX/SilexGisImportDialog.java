/* @file SilexGisImportDialog.java
 *
 * @author apgeo
 * @date oct 2026
 *
 * @brief TopoDroid import of a survey from a SilexGIS server
 *
 * The dialog lists the TopoDroid surveys uploaded to the server, grouped by cave.
 * Import downloads the archive of the selected survey into the zip folder and
 * runs the ZIP import on it; the Therion file button downloads the Therion file
 * of the upload and runs the Therion import on it.
 * --------------------------------------------------------
 *  Copyright This software is distributed under GPL-3.0 or later
 *  See the file COPYING.
 * --------------------------------------------------------
 */
package com.topodroid.TDX;

import com.topodroid.ui.MyDialog;
import com.topodroid.prefs.TDSetting;

import android.os.Bundle;
import android.os.AsyncTask;
import android.content.Context;

import android.widget.TextView;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.BaseAdapter;
import android.widget.AdapterView;

import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.Typeface;

import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;

import java.io.File;
import java.util.ArrayList;

public class SilexGisImportDialog extends MyDialog
                                  implements View.OnClickListener
                                  , AdapterView.OnItemClickListener
{
  private final MainWindow mParent;
  private final SilexGisClient mClient;

  private ArrayList< SilexGisClient.Upload > mUploads = null; // uploads of the server, sorted (null until they are loaded)
  private final ArrayList< Object > mRows = new ArrayList<>(); // rows of the list: a cave name (String) or an upload
  private SilexGisClient.Upload mSelected = null;

  private EditText    mETfilter;
  private TextView    mTVstatus;
  private ListView    mList;
  private ProgressBar mProgress;
  private Button      mBtnOk;
  private Button      mBtnTh;
  private RowAdapter  mAdapter;

  private ListTask     mListTask = null;
  private DownloadTask mDownload = null;

  /** cstr
   * @param context   context
   * @param parent    main window, which runs the import
   */
  public SilexGisImportDialog( Context context, MainWindow parent )
  {
    super( context, null, 0 ); // 0: no help resource
    mParent = parent;
    mClient = new SilexGisClient( TDSetting.mSilexGisUrl, TDSetting.mSilexGisKey );
  }

  /** @return true if the server URL and the access key are set: the import from the server is offered
   */
  public static boolean isAvailable()
  {
    SilexGisClient client = new SilexGisClient( TDSetting.mSilexGisUrl, TDSetting.mSilexGisKey );
    return client.isConfigured();
  }

  // ---------------------------------------------------------------

  @Override
  protected void onCreate( Bundle savedInstanceState )
  {
    super.onCreate( savedInstanceState );
    initLayout( R.layout.silexgis_import_dialog, R.string.silexgis_import_title );

    mETfilter = (EditText) findViewById( R.id.silexgis_filter );
    mTVstatus = (TextView) findViewById( R.id.silexgis_status );
    mList     = (ListView) findViewById( R.id.silexgis_list );
    mProgress = (ProgressBar) findViewById( R.id.silexgis_progress );
    mAdapter  = new RowAdapter();
    mList.setAdapter( mAdapter );
    mList.setOnItemClickListener( this );

    mETfilter.addTextChangedListener( new TextWatcher() {
      @Override public void beforeTextChanged( CharSequence s, int start, int count, int after ) { }
      @Override public void onTextChanged( CharSequence s, int start, int before, int count ) { }
      @Override public void afterTextChanged( Editable s ) { updateRows(); }
    } );

    mBtnOk = (Button) findViewById( R.id.button_ok );
    mBtnTh = (Button) findViewById( R.id.button_th );
    mBtnOk.setOnClickListener( this );
    mBtnTh.setOnClickListener( this );
    ( (Button) findViewById( R.id.button_cancel ) ).setOnClickListener( this );
    ( (Button) findViewById( R.id.button_help ) ).setVisibility( View.GONE );

    mTVstatus.setText( R.string.pleasewait );
    mListTask = new ListTask( this, mClient );
    mListTask.execute();
  }

  /** dismiss the dialog, stopping what is running (also on the BACK key, which dismisses the dialog)
   */
  @Override
  public void dismiss()
  {
    if ( mListTask != null ) mListTask.cancel( true );
    if ( mDownload != null ) mDownload.cancel( true );
    super.dismiss();
  }

  // ---------------------------------------------------------------
  // the list

  /** rebuild the rows of the list from the uploads and the filter text, grouped by cave
   */
  private void updateRows()
  {
    mRows.clear();
    if ( mUploads != null ) {
      String cave = null;
      for ( SilexGisClient.Upload up : SilexGisClient.filterUploads( mUploads, mETfilter.getText().toString() ) ) {
        if ( cave == null || ! cave.equals( up.caveName ) ) {
          cave = up.caveName;
          mRows.add( cave );
        }
        mRows.add( up );
      }
      mTVstatus.setText( mRows.isEmpty()? resString( R.string.silexgis_import_empty ) : "" );
      mTVstatus.setVisibility( mRows.isEmpty()? View.VISIBLE : View.GONE );
    }
    if ( mSelected != null && ! mRows.contains( mSelected ) ) mSelected = null;
    mAdapter.notifyDataSetChanged();
  }

  /** the server refused the access key: ask a new key, then open the dialog again
   */
  private void keyRefused()
  {
    final Context context = mContext;
    final MainWindow parent = mParent;
    dismiss();
    SilexGisSettingsDialog.showKeyRefused( context, new Runnable() {
      @Override public void run() { (new SilexGisImportDialog( context, parent )).show(); }
    } );
  }

  /** the uploads have been loaded
   * @param res   result of the list call
   */
  private void showList( SilexGisClient.Result res )
  {
    mListTask = null;
    ArrayList< SilexGisClient.Upload > uploads = res.isOk()? SilexGisClient.parseUploads( res.json ) : null;
    if ( res.isOk() && uploads == null ) {
      mTVstatus.setText( R.string.silexgis_bad_response );
      return;
    }
    if ( ! res.isOk() ) {
      if ( res.kind == SilexGisClient.KEY_REFUSED ) {
        keyRefused();
        return;
      }
      mTVstatus.setText( SilexGisSettingsDialog.errorMessage( mContext.getResources(), res ) );
      return;
    }
    SilexGisClient.sortUploads( uploads );
    mUploads = uploads;
    updateRows();
  }

  /** the list of the server, off the UI thread
   */
  private static class ListTask extends AsyncTask< Void, Void, SilexGisClient.Result >
  {
    private final SilexGisImportDialog mDialog;
    private final SilexGisClient mClient;

    ListTask( SilexGisImportDialog dialog, SilexGisClient client )
    {
      mDialog = dialog;
      mClient = client;
    }

    @Override
    protected SilexGisClient.Result doInBackground( Void... args )
    {
      return mClient.listUploads( "topodroid", 100 );
    }

    @Override
    protected void onPostExecute( SilexGisClient.Result res )
    {
      if ( mDialog.isShowing() ) mDialog.showList( res );
    }
  }

  /** adapter of the rows: a cave name is a heading, an upload is a selectable row
   */
  private class RowAdapter extends BaseAdapter
  {
    @Override public int getCount() { return mRows.size(); }
    @Override public Object getItem( int pos ) { return mRows.get( pos ); }
    @Override public long getItemId( int pos ) { return pos; }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled( int pos ) { return mRows.get( pos ) instanceof SilexGisClient.Upload; }

    @Override
    public View getView( int pos, View convert, ViewGroup parent )
    {
      TextView tv = (TextView) convert;
      if ( tv == null ) {
        tv = (TextView) LayoutInflater.from( mContext ).inflate( R.layout.silexgis_import_row, parent, false );
      }
      Object row = mRows.get( pos );
      if ( row instanceof SilexGisClient.Upload ) {
        SilexGisClient.Upload up = (SilexGisClient.Upload) row;
        tv.setText( up.title + "\n" + String.format( resString( R.string.silexgis_import_row ), SilexGisClient.shortTime( up.createdAt ), up.uploader, SilexGisClient.sizeText( up.size, -1L ) ) );
        tv.setTypeface( Typeface.DEFAULT );
        tv.setBackgroundColor( ( up == mSelected )? 0x60808080 : 0 );
      } else {
        tv.setText( (String) row );
        tv.setTypeface( Typeface.DEFAULT_BOLD );
        tv.setBackgroundColor( 0 );
      }
      return tv;
    }
  }

  @Override
  public void onItemClick( AdapterView< ? > parent, View view, int pos, long id )
  {
    Object row = mRows.get( pos );
    if ( row instanceof SilexGisClient.Upload ) {
      mSelected = (SilexGisClient.Upload) row;
      mAdapter.notifyDataSetChanged();
    }
  }

  // ---------------------------------------------------------------
  // the download

  /** respond to a user tap
   * @param v  tapped view
   */
  @Override
  public void onClick( View v )
  {
    int id = v.getId();
    if ( id == R.id.button_cancel ) {
      dismiss();
      return;
    }
    if ( mDownload != null ) return; // one download at a time
    if ( mSelected == null ) {
      TDToast.makeWarn( R.string.silexgis_import_select );
      return;
    }
    if ( id == R.id.button_ok ) {
      File dir = new File( TDPath.getZipFile( "" ) ); // the zip folder
      String name = SilexGisClient.safeFileName( mSelected.archiveName.isEmpty()? mSelected.surveyName : mSelected.archiveName, ".zip" );
      startDownload( mSelected.archiveUrl, SilexGisClient.newFile( dir, name ), false );
    } else if ( id == R.id.button_th ) {
      if ( mSelected.therion.isEmpty() ) {
        TDToast.makeWarn( R.string.silexgis_import_no_th );
        return;
      }
      String[] th = mSelected.therion.get( 0 );
      File dir = mContext.getCacheDir();
      File file = new File( dir, SilexGisClient.safeFileName( th[0].isEmpty()? mSelected.surveyName : th[0], ".th" ) );
      startDownload( th[1], file, true );
    }
  }

  /** start the download of a file
   * @param url      content URL
   * @param file     destination file
   * @param therion  whether the file is a Therion file (else it is the ZIP archive)
   */
  private void startDownload( String url, File file, boolean therion )
  {
    File dir = file.getParentFile();
    if ( dir != null && ! dir.exists() ) dir.mkdirs();
    mBtnOk.setEnabled( false );
    mBtnTh.setEnabled( false );
    mProgress.setIndeterminate( true );
    mProgress.setVisibility( View.VISIBLE );
    mTVstatus.setVisibility( View.VISIBLE );
    mTVstatus.setText( String.format( resString( R.string.silexgis_import_downloading ), file.getName(), "" ) );
    mDownload = new DownloadTask( this, mClient, url, file, therion );
    mDownload.execute();
  }

  /** show the progress of the download
   * @param file   destination file
   * @param done   bytes received
   * @param total  bytes expected (-1 if not known)
   */
  private void showProgress( File file, long done, long total )
  {
    if ( total > 0 ) {
      mProgress.setIndeterminate( false );
      mProgress.setMax( 1000 );
      mProgress.setProgress( (int)( ( 1000L * done ) / total ) );
    }
    mTVstatus.setText( String.format( resString( R.string.silexgis_import_downloading ), file.getName(), SilexGisClient.sizeText( done, total ) ) );
  }

  /** the download is over: import the file, or say why not
   * @param res      result of the download
   * @param file     destination file
   * @param therion  whether the file is a Therion file
   */
  private void downloadDone( SilexGisClient.Result res, File file, boolean therion )
  {
    mDownload = null;
    mProgress.setVisibility( View.GONE );
    mBtnOk.setEnabled( true );
    mBtnTh.setEnabled( true );
    if ( res.isOk() ) {
      dismiss();
      if ( therion ) {
        mParent.importTherionFile( file );
      } else {
        // the existing importer: its checks and its refusal of a survey that exists already. A refused file is not kept
        if ( ! mParent.importZipFile( file.getName() ) ) file.delete();
      }
      return;
    }
    if ( res.kind == SilexGisClient.KEY_REFUSED ) {
      keyRefused();
      return;
    }
    mTVstatus.setText( res.kind == SilexGisClient.FILE_ERROR
      ? String.format( resString( R.string.silexgis_file_error ), res.message() )
      : SilexGisSettingsDialog.errorMessage( mContext.getResources(), res ) );
  }

  /** the download of a file, off the UI thread
   */
  private static class DownloadTask extends AsyncTask< Void, Long, SilexGisClient.Result >
  {
    private final SilexGisImportDialog mDialog;
    private final SilexGisClient mClient;
    private final String  mUrl;
    private final File    mFile;
    private final boolean mTherion;
    private long mLast = 0L; // time of the last progress report [ms]

    DownloadTask( SilexGisImportDialog dialog, SilexGisClient client, String url, File file, boolean therion )
    {
      mDialog  = dialog;
      mClient  = client;
      mUrl     = url;
      mFile    = file;
      mTherion = therion;
    }

    @Override
    protected SilexGisClient.Result doInBackground( Void... args )
    {
      return mClient.download( mUrl, mFile, new SilexGisClient.Progress() {
        @Override public boolean onProgress( long done, long total ) {
          long now = System.currentTimeMillis();
          if ( now - mLast >= 100L ) { // not more than ten reports a second
            mLast = now;
            publishProgress( done, total );
          }
          return ! isCancelled();
        }
      } );
    }

    @Override
    protected void onProgressUpdate( Long... progress )
    {
      if ( mDialog.isShowing() ) mDialog.showProgress( mFile, progress[0], progress[1] );
    }

    @Override
    protected void onPostExecute( SilexGisClient.Result res )
    {
      if ( mDialog.isShowing() ) mDialog.downloadDone( res, mFile, mTherion );
    }
  }

}
