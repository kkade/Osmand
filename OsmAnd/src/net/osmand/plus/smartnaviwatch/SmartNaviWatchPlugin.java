package net.osmand.plus.smartnaviwatch;

import android.app.Activity;

import net.osmand.Location;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.util.MapUtils;

import ch.hsr.navigationmessagingapi.IMessageListener;
import ch.hsr.navigationmessagingapi.MessageTypes;
import ch.hsr.navigationmessagingapi.NavigationMessage;
import ch.hsr.navigationmessagingapi.services.NavigationServiceConnector;

public class SmartNaviWatchPlugin extends OsmandPlugin implements IMessageListener{

    // Connections to the OsmAnd Application Framework
    private OsmandApplication application;
    private RoutingHelper routing;
    private OsmAndLocationProvider location;

    // Connection to the Messaging API
    private NavigationServiceConnector messageService;

    // Data with information about the current routing progress
    private RouteCalculationResult.NextDirectionInfo currentInfo;
    private Location lastKnownLocation;

    /**
     * Removes all data from the current navigation status
     */
    private void cleanRouteData() {
        lastKnownLocation = null;
        currentInfo = null;
    }

    /**
     * Gets a connector to a navigation service that can send and receive messages
     * @return API connector
     */
    private NavigationServiceConnector getServiceConnector() {
        if (messageService == null)
        {
            messageService = new NavigationServiceConnector(application.getApplicationContext());
        }
        return messageService;
    }

    /**
     * Compares the two passed navigation steps and returns true if they are different
     * @param info1 First step, can be null
     * @param info2 Second step, can be null
     * @return boolean, that indicates if a difference was found
     */
    private boolean stepsAreDifferent(RouteCalculationResult.NextDirectionInfo info1, RouteCalculationResult.NextDirectionInfo info2)
    {
        // Difference by null
        if ((info1 != null && info2 == null) || info1 == null && info2 != null) return true;

        // Not different because of reference equality
        if (info1 == info2) return false;

        // If the other checks failed, check for proximity
        Location p1 = routing.getRoute().getImmutableAllLocations().get(info1.directionInfo.routePointOffset);
        Location p2 = routing.getRoute().getImmutableAllLocations().get(info2.directionInfo.routePointOffset);
        double delta = MapUtils.getDistance(p1.getLatitude(), p1.getLongitude(), p2.getLatitude(), p2.getLongitude());

        return delta >= 0.2; // 0.2 meters are considered equal
    }

    /**
     * Checks for a necessity to update the user and the infos about the current step
     */
    private void updateNavigationSteps() {
        RouteCalculationResult.NextDirectionInfo n = routing.getNextRouteDirectionInfo(new RouteCalculationResult.NextDirectionInfo(), false);

        if(stepsAreDifferent(currentInfo, n)) {
            currentInfo = n;

          if (currentInfo != null) application.showShortToastMessage("new step: " + currentInfo.directionInfo.getDescriptionRoute(application));
        }

        // Check distance to the current step, if smaller than a certain radius,
        // tell the user to execute the step
        if (currentInfo != null && lastKnownLocation != null) {
            Location p1 = routing.getRoute().getImmutableAllLocations().get(currentInfo.directionInfo.routePointOffset);

            double delta = MapUtils.getDistance(p1.getLatitude(), p1.getLongitude(), lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());

            // Closer than 5m, let's notify the user
            if (delta < 5) {
                application.showToastMessage("exec step: " + currentInfo.directionInfo.getDescriptionRoute(application));
            }
        }
    }

    /**
     * Called when a message from the navigation message api is received
     * @param message Message passed from the API
     */
    @Override
    public void messageReceived(NavigationMessage message) {
        application.showToastMessage(message.getMessageType());
    }

    /**
     * Adapts to routing events (start and end of navigation)
     */
    private class RoutingAdapter implements RoutingHelper.IRouteInformationListener {

        /**
         * Adapts to routing events (start and end of navigation)
         * @param newRoute Indicates wether a completely new route or an alternate route to an existing
         *                 route was used.
         */
        @Override
        public void newRouteIsCalculated(boolean newRoute) {
            cleanRouteData();

            sendMessage(MessageTypes.NewRouteMessage, new Integer(0));
        }

        /**
         * Navigation has ended
         */
        @Override
        public void routeWasCancelled() {
            cleanRouteData();

            //sendMessage(MessageTypes.CancellationMessage, null);
        }
    }

    /**
     * Sends a new message via the connected API
     * @param messageType Type of the message
     * @param payload Content of the message
     */
    private void sendMessage(String messageType, Object payload) {
        application.showToastMessage("send: " + messageType);
        NavigationMessage msg = NavigationMessage.create(messageType, payload);
        getServiceConnector().sendMessage(msg);
    }

    /**
     * Adapts to the location service from OsmAnd. Receives updates when the users location
     * changes.
     */
    private class LocationAdapter implements OsmAndLocationProvider.OsmAndLocationListener{
        /**
         * New location is known. Store it and check if updates should be sent to the user
         * @param location Location info
         */
        @Override
        public void updateLocation(Location location) {
            if (locationChangeIsSignificant(lastKnownLocation, location)) {
                lastKnownLocation = location;

                updateNavigationSteps();
            }
        }
    }

    /**
     * Checks wether the two locations differ significantly from each other or not
     * @param lastKnownLocation Location 1
     * @param location Location 2
     * @return True if significant change found
     */
    private boolean locationChangeIsSignificant(Location lastKnownLocation, Location location) {
        if (lastKnownLocation == null && location != null) return true;
        if (lastKnownLocation != null && location == null) return true;
        if (lastKnownLocation == null && location == null) return false;

        double delta = MapUtils.getDistance(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude(), location.getLatitude(), location.getLongitude());

        return delta >= 4;
    }

    /**
     * Creates a new instance of SmartNaviWatchPlugin
     * @param app OsmAnd application this plugin runs in
     */
    public SmartNaviWatchPlugin(OsmandApplication app)
    {
        application = app;
    }

    /**
     * Initializes the plugin and creates references to the OsmAnd and NavigationAPI services
     * @param app OsmAnd application this plugin runs in
     * @param activity Activity that initialized the plugin
     * @return Initialization success
     */
    @Override
    public boolean init(OsmandApplication app, Activity activity) {
        // Listen for new routes and cancellation
        routing = app.getRoutingHelper();
        routing.addListener(new RoutingAdapter());

        // Listen for location updates
        location = app.getLocationProvider();
        location.addLocationListener(new LocationAdapter());

        // TODO Get reference to BinaryMapIndexReader for map updates

        // Listen to messages
        getServiceConnector().addMessageListener(this);

        return true;
    }

    /**
     * Gets the activity that should be started for additional settings. Not Used.
     * @return null
     */
    @Override
    public Class<? extends Activity> getSettingsActivity() {
        return null;
    }

    /**
     * Gets the plugin id
     * @return Plugin id
     */
    @Override
    public String getId() {
        return "ch.hsr.smart-navi-watch";
    }

    /**
     * Gets the plugin description
     * @return Plugin description
     */
    @Override
    public String getDescription() {
        return "Sends data to your watch.";
    }

    /**
     * Gets the display name of the plugin
     * @return Plugin display name
     */
    @Override
    public String getName() {
        return "Smart Navi Watch";
    }

    /**
     * Gets the asset name for the icon
     * @return Icon asset name
     */
    @Override
    public int getAssetResourceName() {
        return R.drawable.parking_position;
    }
}
