package net.osmand.plus.smartnaviwatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.util.Log;

import net.osmand.Location;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.util.Algorithms;

import java.util.List;

import ch.hsr.navigationmessagingapi.IMessageListener;
import ch.hsr.navigationmessagingapi.MessageEndPoint;
import ch.hsr.navigationmessagingapi.NavigationMessage;
import ch.hsr.navigationmessagingapi.services.NavigationServiceConnector;

public class SmartNaviWatchPlugin extends OsmandPlugin implements IMessageListener{

    private OsmandApplication application;
    private RoutingHelper routing;
    private RouteCalculationResult.NextDirectionInfo currentInfo;
    private OsmAndLocationProvider location;
    private NavigationServiceConnector messageService;

    private NavigationServiceConnector getServiceConnector() {
        if (messageService == null)
        {
            messageService = new NavigationServiceConnector(application.getApplicationContext());
        }
        return messageService;
    }

    private boolean stepsAreDifferent(RouteCalculationResult.NextDirectionInfo oldInfo, RouteCalculationResult.NextDirectionInfo newDirection) {
        return true;
    }

    private void updateNavigationSteps() {
        application.showShortToastMessage("update steps");
        RouteCalculationResult.NextDirectionInfo n = routing.getNextRouteDirectionInfo(new RouteCalculationResult.NextDirectionInfo(), false);

        if(stepsAreDifferent(currentInfo, n)) {
            currentInfo = n;

          if (currentInfo != null) application.showShortToastMessage("w: " + currentInfo.directionInfo.getDescriptionRoute(application));
        }
    }

    @Override
    public void messageReceived(NavigationMessage message) {
        application.showToastMessage(message.getMessageType());
    }

    private class RoutingAdapter implements RoutingHelper.IRouteInformationListener {
        @Override
        public void newRouteIsCalculated(boolean newRoute) {
            // clean the old lists
            updateNavigationSteps();
            NavigationMessage msg = NavigationMessage.create("/navigation/route/new", new Integer(3));

            getServiceConnector().sendMessage(msg);
        }

        @Override
        public void routeWasCancelled() {

        }
    }

    private class LocationAdapter implements OsmAndLocationProvider.OsmAndLocationListener{
        @Override
        public void updateLocation(Location location) {
            //updateNavigationSteps();
        }
    }

    public SmartNaviWatchPlugin(OsmandApplication app)
    {
        application = app;
    }

    @Override
    public boolean init(OsmandApplication app, Activity activity) {
        routing = app.getRoutingHelper();
        location = app.getLocationProvider();

        routing.addListener(new RoutingAdapter());
        location.addLocationListener(new LocationAdapter());

        // Initialize service connector
        getServiceConnector().addMessageListener(this);

        return true;
    }

    @Override
    public Class<? extends Activity> getSettingsActivity() {
        return null;
    }

    @Override
    public String getId() {
        return "ch.hsr.smart-navi-watch";
    }

    @Override
    public String getDescription() {
        return "Sends data to your watch.";
    }

    @Override
    public String getName() {
        return "Smart Navi Watch";
    }

    @Override
    public int getAssetResourceName() {
        return R.drawable.parking_position;
    }
}
