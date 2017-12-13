package com.mapbox.services.android.navigation.ui.v5;

import android.app.Activity;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomSheetBehavior;
import android.support.design.widget.CoordinatorLayout;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AppCompatDelegate;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.annotations.MarkerOptions;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.plugins.locationlayer.LocationLayerMode;
import com.mapbox.mapboxsdk.plugins.locationlayer.LocationLayerPlugin;
import com.mapbox.services.android.navigation.ui.v5.camera.NavigationCamera;
import com.mapbox.services.android.navigation.ui.v5.instruction.InstructionView;
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute;
import com.mapbox.services.android.navigation.ui.v5.route.RouteViewModel;
import com.mapbox.services.android.navigation.ui.v5.summary.SummaryBottomSheet;
import com.mapbox.services.android.navigation.v5.location.MockLocationEngine;
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigation;
import com.mapbox.services.android.navigation.v5.navigation.NavigationConstants;
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute;
import com.mapbox.services.android.telemetry.location.LocationEngine;

/**
 * View that creates the drop-in UI.
 * <p>
 * Once started, this view will check if the {@link Activity} that inflated
 * it was launched with a {@link DirectionsRoute}.
 * <p>
 * Or, if not found, this view will look for a set of {@link Point} coordinates.
 * In the latter case, a new {@link DirectionsRoute} will be retrieved from {@link NavigationRoute}.
 * <p>
 * Once valid data is obtained, this activity will immediately begin navigation
 * with {@link MapboxNavigation}.
 * <p>
 * If launched with the simulation boolean set to true, a {@link MockLocationEngine}
 * will be initialized and begin pushing updates.
 * <p>
 * This activity requires user permissions ACCESS_FINE_LOCATION
 * and ACCESS_COARSE_LOCATION have already been granted.
 * <p>
 * A Mapbox access token must also be set by the developer (to initialize navigation).
 *
 * @since 0.7.0
 */
public class NavigationView extends CoordinatorLayout implements LifecycleObserver,
  OnMapReadyCallback, MapboxMap.OnScrollListener, NavigationContract.View {

  private MapView mapView;
  private InstructionView instructionView;
  private SummaryBottomSheet summaryBottomSheet;
  private BottomSheetBehavior summaryBehavior;
  private ImageButton cancelBtn;
  private RecenterButton recenterBtn;

  private NavigationPresenter navigationPresenter;
  private NavigationViewModel navigationViewModel;
  private RouteViewModel routeViewModel;
  private LocationViewModel locationViewModel;
  private MapboxMap map;
  private NavigationMapRoute mapRoute;
  private NavigationCamera camera;
  private LocationLayerPlugin locationLayer;
  private NavigationViewListener navigationListener;
  private boolean resumeState;
  private RouteListener routeListener;

  public NavigationView(Context context) {
    this(context, null);
  }

  public NavigationView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, -1);
  }

  public NavigationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO);
    ThemeSwitcher.setTheme(getContext(), attrs);
    init();
  }

  /**
   * Uses savedInstanceState as a cue to restore state (if not null).
   *
   * @param savedInstanceState to restore state if not null
   */
  public void onCreate(@Nullable Bundle savedInstanceState) {
    resumeState = savedInstanceState != null;
    mapView.onCreate(savedInstanceState);
  }

  /**
   * Low memory must be reported so the {@link MapView}
   * can react appropriately.
   */
  public void onLowMemory() {
    mapView.onLowMemory();
  }

  /**
   * If the instruction list is showing and onBackPressed is called,
   * hide the instruction list and do not hide the activity or fragment.
   *
   * @return true if back press handled, false if not
   */
  public boolean onBackPressed() {
    if (instructionView.isShowingInstructionList()) {
      instructionView.hideInstructionList();
      return true;
    }
    return false;
  }

  /**
   * Used to store the bottomsheet state and re-center
   * button visibility.  As well as anything the {@link MapView}
   * needs to store in the bundle.
   *
   * @param outState to store state variables
   */
  public void onSaveInstanceState(Bundle outState) {
    outState.putInt(getContext().getString(R.string.bottom_sheet_state),
      summaryBehavior.getState());
    outState.putBoolean(getContext().getString(R.string.recenter_btn_visible),
      recenterBtn.getVisibility() == View.VISIBLE);
    mapView.onSaveInstanceState(outState);
  }

  /**
   * Used to restore the bottomsheet state and re-center
   * button visibility.  As well as the {@link MapView}
   * position prior to rotation.
   *
   * @param savedInstanceState to extract state variables
   */
  public void onRestoreInstanceState(Bundle savedInstanceState) {
    boolean isVisible = savedInstanceState.getBoolean(getContext().getString(R.string.recenter_btn_visible));
    recenterBtn.setVisibility(isVisible ? View.VISIBLE : View.INVISIBLE);
    int bottomSheetState = savedInstanceState.getInt(getContext().getString(R.string.bottom_sheet_state));
    resetBottomSheetState(bottomSheetState);
  }

  /**
   * Fired after the map is ready, this is our cue to finish
   * setting up the rest of the plugins / location engine.
   * <p>
   * Also, we check for launch data (coordinates or route).
   *
   * @param mapboxMap used for route, camera, and location UI
   * @since 0.6.0
   */
  @Override
  public void onMapReady(MapboxMap mapboxMap) {
    map = mapboxMap;
    map.setPadding(0, 0, 0, summaryBottomSheet.getHeight());
    ThemeSwitcher.setMapStyle(getContext(), map, new MapboxMap.OnStyleLoadedListener() {
      @Override
      public void onStyleLoaded(String style) {
        initRoute();
        initLocationLayer();
        initLifecycleObservers();
        initNavigationPresenter();
        initClickListeners();
        map.setOnScrollListener(NavigationView.this);
        navigationListener.onNavigationReady();
      }
    });
  }

  /**
   * Listener this activity sets on the {@link MapboxMap}.
   * <p>
   * Used as a cue to hide the {@link SummaryBottomSheet} and stop the
   * camera from following location updates.
   *
   * @since 0.6.0
   */
  @Override
  public void onScroll() {
    if (summaryBehavior.getState() != BottomSheetBehavior.STATE_HIDDEN) {
      navigationPresenter.onMapScroll();
    }
  }

  @Override
  public void setSummaryBehaviorState(int state) {
    summaryBehavior.setState(state);
  }

  @Override
  public void setSummaryBehaviorHideable(boolean isHideable) {
    summaryBehavior.setHideable(isHideable);
  }

  @Override
  public void setCameraTrackingEnabled(boolean isEnabled) {
    camera.setCameraTrackingLocation(isEnabled);
  }

  @Override
  public void resetCameraPosition() {
    camera.resetCameraPosition();
  }

  @Override
  public void showRecenterBtn() {
    if (summaryBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
      recenterBtn.show();
    }
  }

  @Override
  public void hideRecenterBtn() {
    recenterBtn.hide();
  }

  @Override
  public void drawRoute(DirectionsRoute directionsRoute) {
    mapRoute.addRoute(directionsRoute);
  }

  /**
   * Creates a marker based on the
   * {@link Point} destination coordinate.
   *
   * @param position where the marker should be placed
   */
  @Override
  public void addMarker(Point position) {
    LatLng markerPosition = new LatLng(position.latitude(),
      position.longitude());
    map.addMarker(new MarkerOptions()
      .position(markerPosition)
      .icon(ThemeSwitcher.retrieveMapMarker(getContext())));
  }

  /**
   * Called when the navigation session is finished.
   * Can either be from a cancel event or if the user has arrived at their destination.
   */
  @Override
  public void finishNavigationView() {
    navigationListener.onNavigationFinished();
  }

  /**
   * Should be called when this view is completely initialized.
   *
   * @param options with containing route / coordinate data
   */
  public void startNavigation(NavigationViewOptions options) {
    // Initialize navigation with options from NavigationViewOptions
    navigationViewModel.initializeNavigationOptions(getContext().getApplicationContext(),
      options.navigationOptions().toBuilder().isFromNavigationUi(true).build());
    // Initialize the camera (listens to MapboxNavigation)
    initCamera();
    // Everything is setup, subscribe to model updates
    subscribeViews();
    // Extract the route data which will begin navigation
    routeViewModel.extractLaunchData(options);
  }

  /**
   * Should be called after {@link NavigationView#onCreate(Bundle)}.
   * <p>
   * This method adds the {@link NavigationViewListener},
   * which will fire ready / cancel events for this view.
   *
   * @param navigationViewListener to be set to this view
   */
  public void getNavigationAsync(NavigationViewListener navigationViewListener) {
    this.navigationListener = navigationViewListener;
    mapView.getMapAsync(this);
  }

  /**
   * Used when starting this {@link android.app.Activity}
   * for the first time.
   * <p>
   * Zooms to the beginning of the {@link DirectionsRoute}.
   *
   * @param directionsRoute where camera should move to
   */
  public void startCamera(DirectionsRoute directionsRoute) {
    if (!resumeState) {
      camera.start(directionsRoute);
    }
  }

  /**
   * Used after configuration changes to resume the camera
   * to the last location update from the Navigation SDK.
   *
   * @param location where the camera should move to
   */
  public void resumeCamera(Location location) {
    if (resumeState && recenterBtn.getVisibility() != View.VISIBLE) {
      camera.resume(location);
      resumeState = false;
    }
  }

  /**
   * Sets the route listener
   * @param routeListener to listen for routing events
   */
  public void setRouteListener(RouteListener routeListener) {
    this.routeListener = routeListener;
  }

  private void init() {
    inflate(getContext(), R.layout.navigation_view_layout, this);
    bind();
    initViewModels();
    initNavigationViewObserver();
    initSummaryBottomSheet();
  }

  /**
   * Binds all necessary views.
   */
  private void bind() {
    mapView = findViewById(R.id.mapView);
    instructionView = findViewById(R.id.instructionView);
    summaryBottomSheet = findViewById(R.id.summaryBottomSheet);
    cancelBtn = findViewById(R.id.cancelBtn);
    recenterBtn = findViewById(R.id.recenterBtn);
  }

  private void initViewModels() {
    try {
      locationViewModel = ViewModelProviders.of((FragmentActivity) getContext()).get(LocationViewModel.class);
      routeViewModel = ViewModelProviders.of((FragmentActivity) getContext()).get(RouteViewModel.class);
      navigationViewModel = ViewModelProviders.of((FragmentActivity) getContext()).get(NavigationViewModel.class);
    } catch (ClassCastException exception) {
      throw new ClassCastException("Please ensure that the provided Context is a valid FragmentActivity");
    }
  }

  /**
   * Sets the {@link BottomSheetBehavior} based on the last state stored
   * in {@link Bundle} savedInstanceState.
   *
   * @param bottomSheetState retrieved from savedInstanceState
   */
  private void resetBottomSheetState(int bottomSheetState) {
    boolean isShowing = bottomSheetState == BottomSheetBehavior.STATE_EXPANDED;
    summaryBehavior.setHideable(!isShowing);
    summaryBehavior.setState(bottomSheetState);
  }

  /**
   * Initializes the {@link NavigationMapRoute} to be used to draw the
   * route.
   */
  private void initRoute() {
    int routeStyleRes = ThemeSwitcher.retrieveNavigationViewRouteStyle(getContext());
    mapRoute = new NavigationMapRoute(null, mapView, map,
      routeStyleRes, NavigationConstants.ROUTE_BELOW_LAYER);
  }

  /**
   * Initializes the {@link NavigationCamera} that will be used to follow
   * the {@link Location} updates from {@link MapboxNavigation}.
   */
  private void initCamera() {
    camera = new NavigationCamera(this, map, navigationViewModel.getNavigation());
  }

  /**
   * Initializes the {@link LocationLayerPlugin} to be used to draw the current
   * location.
   */
  @SuppressWarnings( {"MissingPermission"})
  private void initLocationLayer() {
    locationLayer = new LocationLayerPlugin(mapView, map, null);
    locationLayer.setLocationLayerEnabled(LocationLayerMode.NAVIGATION);
  }

  /**
   * Adds this view as a lifecycle observer.
   * This needs to be done earlier than the other observers (prior to the style loading).
   */
  private void initNavigationViewObserver() {
    try {
      ((LifecycleOwner) getContext()).getLifecycle().addObserver(this);
    } catch (ClassCastException exception) {
      throw new ClassCastException("Please ensure that the provided Context is a valid LifecycleOwner");
    }
  }

  /**
   * Add lifecycle observers to ensure these objects properly
   * start / stop based on the Android lifecycle.
   */
  private void initLifecycleObservers() {
    try {
      ((LifecycleOwner) getContext()).getLifecycle().addObserver(locationLayer);
      ((LifecycleOwner) getContext()).getLifecycle().addObserver(locationViewModel);
      ((LifecycleOwner) getContext()).getLifecycle().addObserver(navigationViewModel);
    } catch (ClassCastException exception) {
      throw new ClassCastException("Please ensure that the provided Context is a valid LifecycleOwner");
    }
  }

  /**
   * Initialize a new presenter for this Activity.
   */
  private void initNavigationPresenter() {
    navigationPresenter = new NavigationPresenter(this);
  }

  /**
   * Sets click listeners to all views that need them.
   */
  private void initClickListeners() {
    cancelBtn.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        navigationPresenter.onCancelBtnClick();
      }
    });
    recenterBtn.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        navigationPresenter.onRecenterClick();
      }
    });
  }

  /**
   * Initializes the {@link BottomSheetBehavior} for {@link SummaryBottomSheet}.
   */
  private void initSummaryBottomSheet() {
    summaryBehavior = BottomSheetBehavior.from(summaryBottomSheet);
    summaryBehavior.setHideable(false);
    summaryBehavior.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
      @Override
      public void onStateChanged(@NonNull View bottomSheet, int newState) {
        if (newState == BottomSheetBehavior.STATE_HIDDEN && navigationPresenter != null) {
          navigationPresenter.onSummaryBottomSheetHidden();
        }
      }

      @Override
      public void onSlide(@NonNull View bottomSheet, float slideOffset) {
      }
    });
  }

  @OnLifecycleEvent(Lifecycle.Event.ON_START)
  public void onStart() {
    mapView.onStart();
  }

  @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
  public void onResume() {
    mapView.onResume();
  }

  @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
  public void onPause() {
    mapView.onPause();
  }

  @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
  public void onStop() {
    mapView.onStop();
  }

  @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
  public void onDestroy() {
    mapView.onDestroy();
  }

  /**
   * Initiate observing of ViewModels by Views.
   */
  private void subscribeViews() {
    instructionView.subscribe(navigationViewModel);
    summaryBottomSheet.subscribe(navigationViewModel);

    locationViewModel.rawLocation.observe((LifecycleOwner) getContext(), new Observer<Location>() {
      @Override
      public void onChanged(@Nullable Location location) {
        if (location != null) {
          routeViewModel.updateRawLocation(location);
        }
      }
    });

    locationViewModel.locationEngine.observe((LifecycleOwner) getContext(), new Observer<LocationEngine>() {
      @Override
      public void onChanged(@Nullable LocationEngine locationEngine) {
        if (locationEngine != null) {
          navigationViewModel.updateLocationEngine(locationEngine);
        }
      }
    });

    routeViewModel.route.observe((LifecycleOwner) getContext(), new Observer<DirectionsRoute>() {
      @Override
      public void onChanged(@Nullable DirectionsRoute directionsRoute) {
        if (routeListener != null) {
          routeListener.onRerouteAlong(directionsRoute);
        }

        if (directionsRoute != null) {
          navigationViewModel.updateRoute(directionsRoute);
          locationViewModel.updateRoute(directionsRoute);
          navigationPresenter.onRouteUpdate(directionsRoute);
          startCamera(directionsRoute);
        }
      }
    });

    routeViewModel.destination.observe((LifecycleOwner) getContext(), new Observer<Point>() {
      @Override
      public void onChanged(@Nullable Point point) {
        if (point != null) {
          navigationPresenter.onDestinationUpdate(point);
        }
      }
    });

    navigationViewModel.isRunning.observe((LifecycleOwner) getContext(), new Observer<Boolean>() {
      @Override
      public void onChanged(@Nullable Boolean isRunning) {
        if (isRunning != null) {
          if (!isRunning) {
            navigationListener.onNavigationFinished();
          }
        }
      }
    });

    navigationViewModel.navigationLocation.observe((LifecycleOwner) getContext(), new Observer<Location>() {
      @Override
      public void onChanged(@Nullable Location location) {
        boolean shouldReroute = routeListener == null ? true : routeListener.allowRerouteFrom(location);
        if (location != null && shouldReroute && location.getLongitude() != 0 && location.getLatitude() != 0) {
          if (routeListener != null) {
            routeListener.onRerouteFrom(location);
          }
          locationLayer.forceLocationUpdate(location);
          resumeCamera(location);
        }
      }
    });

    navigationViewModel.newOrigin.observe((LifecycleOwner) getContext(), new Observer<Point>() {
      @Override
      public void onChanged(@Nullable Point newOrigin) {
        if (newOrigin != null) {
          routeViewModel.fetchRouteNewOrigin(newOrigin);
          // To prevent from firing on rotation
          navigationViewModel.newOrigin.setValue(null);
        }
      }
    });
  }
}
