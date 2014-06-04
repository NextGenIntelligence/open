package com.mapzen.activity;

import com.mapzen.MapController;
import com.mapzen.MapzenApplication;
import com.mapzen.R;
import com.mapzen.android.lost.LocationClient;
import com.mapzen.android.lost.LocationListener;
import com.mapzen.android.lost.LocationRequest;
import com.mapzen.core.DataUploadService;
import com.mapzen.core.OSMOauthFragment;
import com.mapzen.core.SettingsFragment;
import com.mapzen.fragment.ListResultsFragment;
import com.mapzen.fragment.MapFragment;
import com.mapzen.search.AutoCompleteAdapter;
import com.mapzen.search.OnPoiClickListener;
import com.mapzen.search.PagerResultsFragment;
import com.mapzen.search.SavedSearch;
import com.mapzen.util.DatabaseHelper;
import com.mapzen.util.DebugDataSubmitter;
import com.mapzen.util.Logger;
import com.mapzen.util.MapzenGPSPromptDialogFragment;
import com.mapzen.util.MapzenProgressDialogFragment;

import com.bugsense.trace.BugSenseHandler;
import com.squareup.okhttp.OkHttpClient;

import org.oscim.android.MapActivity;
import org.oscim.layers.marker.MarkerItem;
import org.oscim.map.Map;
import org.scribe.model.Token;
import org.scribe.model.Verifier;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.SearchView;
import android.widget.Toast;

import java.io.File;
import java.util.Calendar;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;
import static com.mapzen.MapController.getMapController;
import static com.mapzen.android.lost.LocationClient.ConnectionCallbacks;
import static com.mapzen.search.SavedSearch.getSavedSearch;

public class BaseActivity extends MapActivity {
    @Inject MapzenProgressDialogFragment progressDialogFragment;
    public static final int LOCATION_INTERVAL = 1000;
    public static final String COM_MAPZEN_UPDATES_LOCATION = "com.mapzen.updates.location";
    public static final String
            DEBUG_DATA_ENDPOINT = "http://on-the-road.dev.mapzen.com/upload";
    protected DatabaseHelper dbHelper;
    protected DebugDataSubmitter debugDataSubmitter;
    protected LocationClient locationClient;
    private Menu activityMenu;
    private AutoCompleteAdapter autoCompleteAdapter;
    private MenuItem menuItem;
    private MapzenApplication app;
    private MapFragment mapFragment;
    private MapzenGPSPromptDialogFragment gpsPromptDialogFragment;
    private boolean updateMapLocation = true;
    private Token requestToken = null;
    private Verifier verifier = null;
    private LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (updateMapLocation) {
                getMapController().setLocation(location);
                mapFragment.findMe();
            }
            Intent toBroadcast = new Intent(COM_MAPZEN_UPDATES_LOCATION);
            toBroadcast.putExtra("location", location);
            sendBroadcast(toBroadcast);
        }
    };
    protected ConnectionCallbacks connectionCallback = new ConnectionCallbacks() {
        @Override
        public void onConnected(Bundle bundle) {
            getMapController().setZoomLevel(MapController.DEFAULT_ZOOMLEVEL);
            final Location location = locationClient.getLastLocation();
            Logger.d("Last known location: " + location);

            if (location != null) {
                getMapController().setLocation(location);
                mapFragment.findMe();
            } else {
                Toast.makeText(BaseActivity.this, getString(R.string.waiting),
                        Toast.LENGTH_LONG).show();
            }

            LocationRequest locationRequest = LocationRequest.create();
            locationRequest.setInterval(LOCATION_INTERVAL);
            locationClient.requestLocationUpdates(locationRequest, locationListener);
        }

        @Override
        public void onDisconnected() {
            Logger.d("LocationHelper disconnected.");
        }
    };

    private boolean enableActionbar = true;

    protected Executor debugDataExecutor = Executors.newSingleThreadExecutor();

    public void deactivateMapLocationUpdates() {
        updateMapLocation = false;
    }

    public void activateMapLocationUpdates() {
        updateMapLocation = true;
    }

    public LocationListener getLocationListener() {
        return locationListener;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (MapzenApplication) getApplication();
        app.inject(this);
        BugSenseHandler.initAndStartSession(this, "ebfa8fd7");
        BugSenseHandler.addCrashExtraData("OEM", Build.MANUFACTURER);
        setContentView(R.layout.base);
        initMapFragment();
        gpsPromptDialogFragment = new MapzenGPSPromptDialogFragment();
        initMapController();
        initLocationClient();
        initAlarm();
        initSavedSearches();
    }

    @Override
    public void onPause() {
        super.onPause();
        locationClient.disconnect();
        persistSavedSearches();
    }

    @Override
    protected void onResume() {
        super.onResume();
        locationClient.connect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearNotifications();
    }

    private void clearNotifications() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);
        notificationManager.cancel(0);
    }

    public SQLiteDatabase getDb() {
        return app.getDb();
    }

    public boolean isInDebugMode() {
        SharedPreferences prefs = getDefaultSharedPreferences(this);
        return prefs.getBoolean(getString(R.string.settings_key_debug), false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                final Fragment listResultsFragment = getSupportFragmentManager()
                        .findFragmentByTag(ListResultsFragment.TAG);
                if (listResultsFragment != null) {
                    return listResultsFragment.onOptionsItemSelected(item);
                }
                break;
            case R.id.settings:
                final PreferenceFragment settingsFragment = SettingsFragment.newInstance(this);
                getFragmentManager().beginTransaction()
                        .add(R.id.settings, settingsFragment, SettingsFragment.TAG)
                        .addToBackStack(null)
                        .commit();
                return true;
            case R.id.phone_home:
                initDebugDataSubmitter();
                debugDataExecutor.execute(debugDataSubmitter);
                return true;
            case R.id.login:
                FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                Fragment prev = getSupportFragmentManager().findFragmentByTag(OSMOauthFragment.TAG);
                if (prev != null) {
                    ft.remove(prev);
                }
                ft.addToBackStack(null);
                DialogFragment newFragment = OSMOauthFragment.newInstance(this);
                newFragment.show(ft, OSMOauthFragment.TAG);
                return true;
            case R.id.logout:
                SharedPreferences prefs = getSharedPreferences("OAUTH", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.remove("token");
                editor.remove("forced_login");
                editor.commit();
                toggleOSMLogin();
                startActivity(new Intent(this, InitActivity.class));
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }

        return false;
    }

    public void showProgressDialog() {
        if (!progressDialogFragment.isAdded()) {
            progressDialogFragment.show(getSupportFragmentManager(), "dialog");
        }
    }

    public void showGPSPromptDialog() {
        if (!gpsPromptDialogFragment.isAdded()) {
            gpsPromptDialogFragment.show(getSupportFragmentManager(), "gps_dialog");
        }
    }

    public void promptForGPSIfNotEnabled() {
        if (!locationClient.isGPSEnabled()) {
            showGPSPromptDialog();
        }
    }

    public void dismissProgressDialog() {
        progressDialogFragment.dismiss();
    }

    public MapzenProgressDialogFragment getProgressDialogFragment() {
        return progressDialogFragment;
    }

    private void initLocationClient() {
        locationClient = new LocationClient(this, connectionCallback);
    }

    private void initMapFragment() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        mapFragment = (MapFragment) fragmentManager.findFragmentById(R.id.map_fragment);
        mapFragment.setAct(this);
        mapFragment.setOnPoiClickListener(new OnPoiClickListener() {
            @Override
            public void onPoiClick(int index, MarkerItem item) {
                final PagerResultsFragment pagerResultsFragment = getPagerResultsFragment();
                pagerResultsFragment.setCurrentItem(index);
            }
        });
    }

    private PagerResultsFragment getPagerResultsFragment() {
        return (PagerResultsFragment) getSupportFragmentManager()
                .findFragmentByTag(PagerResultsFragment.TAG);
    }

    public MapFragment getMapFragment() {
        return mapFragment;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        activityMenu = menu;
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        SearchManager searchManager =
                (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        menuItem = menu.findItem(R.id.search);
        menuItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                final PagerResultsFragment pagerResultsFragment = getPagerResultsFragment();
                final ListResultsFragment listResultsFragment = (ListResultsFragment)
                        getSupportFragmentManager().findFragmentByTag(ListResultsFragment.TAG);
                if (pagerResultsFragment != null && pagerResultsFragment.isAdded()
                        && listResultsFragment == null) {
                    getSupportFragmentManager().beginTransaction().remove(pagerResultsFragment)
                            .commit();
                }
                return true;
            }
        });

        final SearchView searchView = (SearchView) menuItem.getActionView();
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        initSavedSearchAutoComplete(searchView);
        setupAdapter(searchView);
        searchView.setOnQueryTextListener(autoCompleteAdapter);

        if (!app.getCurrentSearchTerm().isEmpty()) {
            menuItem.expandActionView();
            Logger.d("search: " + app.getCurrentSearchTerm());
            searchView.setQuery(app.getCurrentSearchTerm(), false);
            searchView.clearFocus();
        }

        Uri data = getIntent().getData();
        if (data != null) {
            Logger.d("data = " + data.toString());
            if (data.toString().contains("geo:")) {
                handleGeoIntent(searchView, data);
            } else if (data.toString().contains("maps.google.com")) {
                handleMapsIntent(searchView, data);
            }
        }
        toggleOSMLogin();

        return true;
    }

    /**
     * Sets auto-complete threshold to 0. Enables drop-down even when text view is empty. Triggers
     * {@link AutoCompleteAdapter} when search view gets focus. Uses resource black magic to get a
     * reference to the {@link AutoCompleteTextView} inside the {@link SearchView}.
     */
    private void initSavedSearchAutoComplete(final SearchView searchView) {
        final AutoCompleteTextView autoCompleteTextView =
                (AutoCompleteTextView) searchView.findViewById(searchView.getContext()
                        .getResources().getIdentifier("android:id/search_src_text", null, null));
        autoCompleteTextView.setThreshold(0);
        searchView.setOnQueryTextFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    menuItem.expandActionView();
                    autoCompleteAdapter.loadSavedSearches();
                    searchView.setQuery("", false);
                }
            }
        });
    }

    private void toggleOSMLogin() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                MenuItem loginMenu = activityMenu.findItem(R.id.login);
                MenuItem logoutMenu = activityMenu.findItem(R.id.logout);

                if ((app.getAccessToken() != null) || wasForceLoggedIn()) {
                    loginMenu.setVisible(false);
                    logoutMenu.setVisible(true);
                } else {
                    loginMenu.setVisible(true);
                    logoutMenu.setVisible(false);
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        SettingsFragment fragment = (SettingsFragment) getFragmentManager()
                .findFragmentByTag(SettingsFragment.TAG);
        if (fragment != null && fragment.isAdded()) {
            getFragmentManager().beginTransaction()
                    .detach(fragment)
                    .commit();
        } else {
            super.onBackPressed();
        }
        clearNotifications();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(ListResultsFragment.TAG);
        if (fragment != null && fragment.isAdded()) {
            collapseSearchView();
            menu.findItem(R.id.search).setVisible(false);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    private void handleGeoIntent(SearchView searchView, Uri data) {
        if (data.toString().contains("q=")) {
            menuItem.expandActionView();
            String queryString = Uri.decode(data.toString().split("q=")[1]);
            app.setCurrentSearchTerm(queryString);
            searchView.setQuery(queryString, true);
        }
    }

    private void handleMapsIntent(SearchView searchView, Uri data) {
        if (data.toString().contains("q=")) {
            menuItem.expandActionView();
            String queryString = Uri.decode(data.toString().split("q=")[1].split("@")[0]);
            app.setCurrentSearchTerm(queryString);
            searchView.setQuery(queryString, true);
        }
    }

    public Map getMap() {
        return mMap;
    }

    public SearchView getSearchView() {
        return (SearchView) menuItem.getActionView();
    }

    public MenuItem getSearchMenu() {
        return menuItem;
    }

    public void setupAdapter(SearchView searchView) {
        if (autoCompleteAdapter == null) {
            autoCompleteAdapter = new AutoCompleteAdapter(getActionBar().getThemedContext(),
                    this, app.getColumns(), getSupportFragmentManager());
            autoCompleteAdapter.setSearchView(searchView);
            autoCompleteAdapter.setMapFragment(mapFragment);
        }

        searchView.setSuggestionsAdapter(autoCompleteAdapter);
    }

    private void initMapController() {
        MapController mapController = getMapController();
        mapController.setActivity(this);
    }

    public void hideActionBar() {
        collapseSearchView();
        getActionBar().hide();
    }

    public void collapseSearchView() {
        MenuItem searchMenu = getSearchMenu();
        if (searchMenu != null) {
            searchMenu.collapseActionView();
        }
    }

    public void enableActionbar() {
        enableActionbar = true;
    }

    public void disableActionbar() {
        enableActionbar = false;
    }

    public void showActionBar() {
        if (!getActionBar().isShowing() && enableActionbar) {
            getActionBar().show();
        }
    }

    public boolean executeSearchOnMap(String query) {
        final SearchView searchView = (SearchView) menuItem.getActionView();
        searchView.setSuggestionsAdapter(null);

        PagerResultsFragment pagerResultsFragment = PagerResultsFragment.newInstance(this);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.pager_results_container, pagerResultsFragment,
                        PagerResultsFragment.TAG)
                .commit();

        return pagerResultsFragment.executeSearchOnMap(getSearchView(), query);
    }

    public void initDebugDataSubmitter() {
        debugDataSubmitter = new DebugDataSubmitter(this);
        debugDataSubmitter.setClient(new OkHttpClient());
        debugDataSubmitter.setEndpoint(DEBUG_DATA_ENDPOINT);
        debugDataSubmitter.setFile(new File(getDb().getPath()));
    }

    public void setAccessToken(Token accessToken) {
        app.setAccessToken(accessToken);
        toggleOSMLogin();
    }

    public Verifier getVerifier() {
        return verifier;
    }

    public void setVerifier(Verifier verifier) {
        this.verifier = verifier;
    }

    public Token getRequestToken() {
        return requestToken;
    }

    public void setRequestToken(Token requestToken) {
        this.requestToken = requestToken;
    }

    private void initAlarm() {
        Calendar cal = Calendar.getInstance();
        Intent intent = new Intent(this, DataUploadService.class);
        PendingIntent pintent = PendingIntent.getService(this, 0, intent, 0);

        AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        // Start every hour
        alarm.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), 60 * 60 * 1000, pintent);
    }

    private void initSavedSearches() {
        SharedPreferences prefs = getDefaultSharedPreferences(this);
        getSavedSearch().deserialize(prefs.getString(SavedSearch.TAG, ""));
    }

    private void persistSavedSearches() {
        SharedPreferences prefs = getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(SavedSearch.TAG, getSavedSearch().serialize());
        editor.commit();
    }

    private boolean wasForceLoggedIn() {
        SharedPreferences pref = getSharedPreferences("OAUTH", Context.MODE_PRIVATE);
        return pref.getBoolean("forced_login", false);
    }
}
