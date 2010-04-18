package com.yammer.v1;

import com.yammer.v1.YammerData.YammerDataException;
import com.yammer.v1.YammerProxy.YammerProxyException;
import com.yammer.v1.models.Feed;
import com.yammer.v1.models.Message;
import com.yammer.v1.models.Network;
import com.yammer.v1.models.User;
import com.yammer.v1.settings.SettingsEditor;
import com.yammer.v1.YammerProxy;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

public class YammerService extends Service {

  private static final boolean DEBUG = G.DEBUG;

  public static final String INTENT_RESET_ACCOUNT = "com.yammer.v1:RESET_ACCOUNT";
  
  public static final String INTENT_POST_MESSAGE = "com.yammer.v1:POST_MESSAGE";
  public static final String EXTRA_MESSAGE = "message";
  
  public static final String INTENT_AUTHENTICATION_COMPLETE = "com.yammer.v1:AUTHENTICATION_COMPLETE";
  public static final String EXTRA_TOKEN = "token";
  
  public static final String INTENT_ENABLE_NOTIFICATION = "com.yammer.v1:ENABLE_NOTIFICATION";
  public static final String INTENT_DISABLE_NOTIFICATION = "com.yammer.v1:DISABLE_NOTIFICATION";
  
  public static final String INTENT_CHANGE_NETWORK = "com.yammer.v1:CHANGE_NETWORK";
  public static final String EXTRA_NETWORK_ID = "network_id";

  /** Client states **/
  private static int STATE_RAW = -1;
  private static int STATE_INITIALIZED = 0;		
  private static int CLIENT_STATE = STATE_RAW;

  /** Notification types **/
  private static int NOTIFICATION_NEW_MESSAGE = 0;

  // Are we authorized?
  private static boolean authorized = false;
  // Check if an update should be made every 10.5 seconds
  private final long GLOBAL_UPDATE_INTERVAL = 10500;
  private static long lastUpdateTime = 0;
  // Check for application updates once a day
  private Timer timer = new Timer();
  // Maintained JSON objects
  JSONObject jsonMessages = null;
  // Default feed
  int defaultFeedId = 0; /* 0 - All messages */
  // Properties of the current network
  int newMessageCount = 0;
  
  // Semaphone to control write access to json objects above
  private final Semaphore jsonUpdateSemaphore = new Semaphore(1);
  private final IBinder mBinder = new YammerBinder();
  
  private boolean notificationEnabled = true;
  
  private Handler mHandler;
  
  // Wakelock
  PowerManager.WakeLock wakelock = null; 

  SettingsEditor settings;
  private SettingsEditor getSettings() {
    if(null == this.settings) {
      this.settings = new SettingsEditor(getApplicationContext());
    }
    return this.settings;
  }

  /**
   * Class for clients to access.  Because we know this service always
   * runs in the same process as its clients, we don't need to deal with
   * IPC.
   */
  public class YammerBinder extends Binder {
    YammerService getService() {
      return YammerService.this;
    }
  }

  class YammerIntentReceiver extends BroadcastReceiver {
    public YammerIntentReceiver() {
    }
    @Override
    public void onReceive(Context context, final Intent intent) {
      if (DEBUG) Log.d(getClass().getName(), "Intent received: " + intent.getAction());
      if (INTENT_RESET_ACCOUNT.equals(intent.getAction())) {
        // Acquire sempahore to disallow updates
        if ( !jsonUpdateSemaphore.tryAcquire() ) {
          if (DEBUG) Log.d(getClass().getName(), "Could not acquire permit to update semaphore - aborting");
          return;
        }
        
        reset();
        
        // Allow updates again (if authorized)
        jsonUpdateSemaphore.release();
        sendBroadcast(YammerActivity.INTENT_MUST_AUTHENTICATE_DIALOG);
      } else if (INTENT_POST_MESSAGE.equals(intent.getAction())) {
        /*
         * Usually called from external something like the browser
         * when a user tries to share something.
         */
        String message = intent.getExtras().getString(EXTRA_MESSAGE);
        try {
          // message ID is 0 since this is not a reply
          getYammerProxy().postMessage(message, 0);
        } catch (YammerProxy.AccessDeniedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        } catch (YammerProxy.ConnectionProblem e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        } catch (Exception e) {
          e.printStackTrace();
        }
      } else if(INTENT_AUTHENTICATION_COMPLETE.equals(intent.getAction())) {
          updateCurrentUserData();
          authenticationComplete();
          
      } else if(INTENT_ENABLE_NOTIFICATION.equals(intent.getAction())) {
          YammerService.this.notificationEnabled = true;
          
      } else if(INTENT_DISABLE_NOTIFICATION.equals(intent.getAction())) {
        YammerService.this.notificationEnabled = false;

      } else if(INTENT_CHANGE_NETWORK.equals(intent.getAction())) {
        new Thread() {
          public void run() {
            changeNetwork(intent.getLongExtra(EXTRA_NETWORK_ID, 0L));
          }
        }.start();
      }
    }

    private void authenticationComplete() {
      if (DEBUG) Log.d(getClass().getName(), ".authenticationComplete");
      setAuthorized(true);
      sendBroadcast(new Intent(YammerActivity.INTENT_AUTHORIZATION_DONE));
   }

    private void reset() {
      getYammerData().resetData(getCurrentNetworkId());
      getSettings().setCurrentNetworkId(0L);
      resetYammerProxy();
      YammerService.setAuthorized(false);
    }
  };

  @Override
  public void onCreate() {
    if (DEBUG) Log.d(getClass().getName(), "YammerService.onCreate");
    super.onCreate();
    this.mHandler = new Handler();
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
  }


  @Override
  public void onStart(Intent intent, int startId) {
    super.onStart(intent, startId);				
    if (DEBUG) Log.d(getClass().getName(), "YammerService.onStart");
    if (DEBUG) Log.d(getClass().getName(), "Client state: " + YammerService.CLIENT_STATE);
    // Was the service already started?
    if ( YammerService.CLIENT_STATE != STATE_RAW ) {
      if (DEBUG) Log.d(getClass().getName(), "YammerService already started once, so just return");
      // Just return
      return;
    } else {
      if (DEBUG) Log.i(getClass().getName(), "Yammer service is initializing");
      // Service has been started once and considered initialized
      YammerService.CLIENT_STATE = STATE_INITIALIZED;

      if (null == getCurrentNetwork()) {
        sendBroadcast(YammerActivity.INTENT_MUST_AUTHENTICATE_DIALOG);        		        	
      } else {
        setAuthorized(true);
      }

      registerIntents();	        

      // Start the update timer
      timer.scheduleAtFixedRate(
          new TimerTask() {
            public void run() {
              try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                // How long to wait
                long updateTimeout = getSettings().getUpdateTimeout();
                //if (DEBUG) Log.d(getClass().getName(), "updateTimeout: " + (lastUpdateTime + updateTimeout) + ", currentTime: " + System.currentTimeMillis());
                // Is it time to update?
                if (updateTimeout != 0 && (System.currentTimeMillis() > lastUpdateTime + updateTimeout) ) {
                  if (DEBUG) Log.d(getClass().getName(), "Acquiring wakelock");
                  wakelock.acquire();
                  // Time to update
                  reloadMessages(false);
                  lastUpdateTime = System.currentTimeMillis();		        					
                } 
              } catch (Exception e) {
                if (DEBUG) Log.d(getClass().getName(), "An exception occured during updatePublicMessage()");
                e.printStackTrace();
              } finally {
                wakelock.release();
                if (DEBUG) Log.d(getClass().getName(), "Wakelock released");
              }
            }
          }, 0, GLOBAL_UPDATE_INTERVAL
      );

    }
  }

  private void registerIntents() {
    IntentFilter filter = new IntentFilter();
    
    filter.addAction(INTENT_RESET_ACCOUNT);
    filter.addAction(INTENT_POST_MESSAGE);
    filter.addAction(INTENT_AUTHENTICATION_COMPLETE);
    filter.addAction(INTENT_ENABLE_NOTIFICATION);
    filter.addAction(INTENT_DISABLE_NOTIFICATION);
    filter.addAction(INTENT_CHANGE_NETWORK);

    registerReceiver(new YammerIntentReceiver(), filter);
  }

  /**
   * Reset counter holding number of new messages
   */
  public void resetMessageCount() {
    if (DEBUG) Log.d(getClass().getName(), "YammerService.resetMessageCount");
    newMessageCount = 0;
  }

  /**
   * Notify user about new activity or errors
   */
  public void notifyUser(int _message_id, int type) {
    // Only notify when we are not bound to the activity
    if(NOTIFICATION_NEW_MESSAGE == type && !notificationEnabled) {
      return;
    }

    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    // Default icon
    int icon = R.drawable.yammer_notification_icon;

    Notification notification = new Notification(icon,
        getResources().getString(_message_id), 
        System.currentTimeMillis()
    ); 
    notification.ledARGB = 0xff035c99; 
    notification.ledOnMS = 200; 
    notification.ledOffMS = 200; 
    notification.defaults = Notification.DEFAULT_SOUND;        
    notification.flags = Notification.FLAG_SHOW_LIGHTS | Notification.FLAG_AUTO_CANCEL; 

    // Vibrate enabled?
    if(getSettings().getVibrate()) {
      notification.vibrate = new long[] {0, 100, 100, 100, 100, 100};	        	
    }

    if(NOTIFICATION_NEW_MESSAGE == type) {
      // Intent of this notification - launch yammer activity
      Intent intent = new Intent(this, YammerActivity.class);
      PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
      // Only show number of new messages if more than one
      if ( newMessageCount > 1 ) {
        notification.number = newMessageCount;	        	
      }
      notification.setLatestEventInfo(this, 
          getResources().getString(R.string.new_yammer_message),
          getResources().getQuantityString(R.plurals.new_messages_available, newMessageCount, newMessageCount),
          pendingIntent
      );
      
    }

    if (DEBUG) Log.d(getClass().getName(), "Displaying notification - " + newMessageCount + " new messages!");
    nm.notify(R.string.app_name, notification);
  }

  /**
   * Post a message or a reply to the current Yammer Network
   * 
   * @param message - message to post
   * @param messageId - Message being replied to
   * 
   * @throws YammerProxy.AccessDeniedException
   * @throws YammerProxy.ConnectionProblem 
   */
  public void postMessage(final String message, final long messageId) throws YammerProxy.YammerProxyException {
    getYammerProxy().postMessage(message, messageId);
  }

  /**
   * Delete a message from the current Yammer network
   * @param messageId - Delete the message with the given ID
   * @throws YammerProxy.AccessDeniedException
   * @throws YammerProxy.ConnectionProblem 
   */
  public void deleteMessage(final long messageId) throws YammerProxy.YammerProxyException {
    if (DEBUG) Log.d(getClass().getName(), ".deleteMessage");
    // TODO: change to getYammer().deleteMessage(messageId);
    getYammerProxy().deleteResource(getURLBase() + "/api/v1/messages/"+messageId);		
    getYammerData().deleteMessage(messageId);
    // TODO: sendBroadcast(ACTION_MESSAGE_DELETED, messageId);
    sendBroadcast(YammerActivity.INTENT_PUBLIC_TIMELINE_UPDATED);
  }

  public void followUser(final long userId) throws YammerProxy.YammerProxyException {
    if (DEBUG) Log.d(getClass().getName(), "YammerService.followUser");
    // GET https://yammer.com/api/v1/subscriptions/to_user/<id>.json
    if (DEBUG) Log.d(getClass().getName(), "Following user");
    getYammerProxy().followUser(userId);
    if (DEBUG) Log.d(getClass().getName(), "User followed!");
  }

  public void unfollowUser(final long userId) throws YammerProxy.YammerProxyException {
    if (DEBUG) Log.d(getClass().getName(), ".followUser");
    getYammerProxy().unfollowUser(userId);
  }

  private void changeNetwork(long _id) {
    if (DEBUG) Log.d(getClass().getName(), "changeNetwork: " + _id);
    setCurrentNetworkId(_id);
    toastUser(R.string.changing_network_text, getCurrentNetwork().name);
    updateCurrentUserData();
    reloadMessages(true);
  }

  public void updateCurrentUserData() {
    if (DEBUG) Log.i(getClass().getName(), ".updateCurrentUserData");
    try {
      User user = getYammerProxy().getCurrentUser(true);
      getYammerData().saveUsers(user.followedUsers);
      reloadNetworks();
    } catch (YammerProxy.YammerProxyException ex) {
      ex.printStackTrace();
    }
  }

  private void reloadNetworks() {
    if (DEBUG) Log.i(getClass().getName(), ".reloadNetworks");
    try {
      getYammerData().clearNetworks();
      getYammerData().addNetworks(getYammerProxy().getNetworks());
    } catch(YammerProxyException ex) {
      ex.printStackTrace();
    }
    reloadFeeds();
  }

  private void reloadFeeds() {
    if (DEBUG) Log.i(getClass().getName(), ".reloadFeeds");
    try {
      getYammerData().clearFeeds();
      Feed[] feeds = getYammerProxy().getFeeds();
      this.getSettings().setFeed(feeds[0].name);
      getYammerData().addFeeds(feeds);
    } catch(YammerProxyException ex) {
      ex.printStackTrace();
    }
  }

  public void clearMessages() {
    getYammerData().clearMessages();
  }
  
  public void reloadMessages(boolean reloading) {
    if (DEBUG) Log.i(getClass().getName(), ".reloadMessages");
    
    if ( ! isAuthorized() ) {
      if (DEBUG) Log.i(getClass().getName(), "User not authorized - skipping update");
      return;
    }
    
    boolean notificationRequired = false;
    boolean messagesFound = false;
    
    try {
      if ( !jsonUpdateSemaphore.tryAcquire() ) {
        if (DEBUG) Log.d(getClass().getName(), "Could not acquire permit to update semaphore - aborting");
        return;
      }
      
      String messages = getYammerProxy().getMessagesNewerThan(getFeedURL(), getCurrentNetwork().lastMessageId);

      if (DEBUG) Log.d(getClass().getName(), "Messages JSON: " + messages);
      jsonMessages = new JSONObject(messages);

      try {
        if (DEBUG) Log.d(getClass().getName(), "Updating users from references");
        JSONArray references = jsonMessages.getJSONArray("references");
        for( int ii=0; ii < references.length(); ii++ ) {
          try {
            JSONObject reference = references.getJSONObject(ii);
            if(reference.getString("type").equals("user")) {
              getYammerData().addUser(reference);
            }
          } catch( JSONException e ) {
            if (DEBUG) Log.w(getClass().getName(), e.getMessage());
          }
        }
      } catch (JSONException e) {
        if (DEBUG) Log.w(getClass().getName(), e.getMessage());
      } catch (YammerDataException e) {
        if (DEBUG) Log.w(getClass().getName(), e.getMessage());
      }
      
//      try {
//        if (DEBUG) Log.d(getClass().getName(), "Trying to fetch last_seen_message_id");
//        JSONObject meta = jsonMessages.getJSONObject("meta");
//        getSettings().setLastSeenMessageId(meta.getLong("last_seen_message_id"));
//      } catch (JSONException e) {
//        if (DEBUG) Log.w(getClass().getName(), e.getMessage());
//      }     

      try {
        if (DEBUG) Log.d(getClass().getName(), "Updating messages");
        // Retrieve all messages
        JSONArray jsonArray = jsonMessages.getJSONArray("messages");
        // Add all fetched messages tp the database
        for( int ii=0; ii < jsonArray.length(); ii++ ) {
          // Add the message reference to the database
          Message message = getYammerData().addMessage(jsonArray.getJSONObject(ii), getCurrentNetworkId());
          // Is this my own message?
          boolean ownMessage = getCurrentUserId() == message.userId;
          // Only ask if notification is required if none of
          // the previous messages had notification requirement
          if(!notificationRequired) {
            notificationRequired = !ownMessage;
            if (DEBUG) Log.d(getClass().getName(), "Notification required: " + notificationRequired);
          }
          // Only increment message counter if this is not one of our own messages
          if ( !ownMessage ) {
            // If we reach this point, a new message has been received - increment new message counter
            newMessageCount ++;	        				
          }
          messagesFound = true;
        }
        
        getSettings().setUpdatedAt();
      } catch (JSONException e) {
        if (DEBUG) Log.w(getClass().getName(), e.getMessage());
      }			

    } catch (YammerProxyException e) {
      if (DEBUG) Log.w(getClass().getName(), e.getMessage());
      return;
    } catch (JSONException e) {
      if (DEBUG) Log.w(getClass().getName(), e.getMessage());
      return;
    } catch (YammerDataException e) {
      if (DEBUG) Log.w(getClass().getName(), e.getMessage());
      return;
    } catch (Exception e) {
       e.printStackTrace();
    } finally {
      // Release the semaphore
      jsonUpdateSemaphore.release();
    }

    if (messagesFound) {
      if (notificationRequired && !reloading) {
        notifyUser(R.string.new_yammer_message, NOTIFICATION_NEW_MESSAGE);				
      }
      
      sendBroadcast(YammerActivity.INTENT_PUBLIC_TIMELINE_UPDATED);
    }
  }

  private String getFeedURL() throws YammerData.YammerDataException {
    return getYammerData().getURLForFeed(getCurrentNetworkId(), getSettings().getFeed());
  }
  
  private String getURLBase() {
    if (OAuthCustom.BASE_URL != null)
      return OAuthCustom.BASE_URL;
    return getSettings().getUrl();
  }

  @Override
  public void onDestroy() {
    if (DEBUG) Log.d(getClass().getName(), "YammerService.onDestroy");
    super.onDestroy();
    // It seems we were destroyed for some reason, so set the state to raw
    YammerService.CLIENT_STATE = STATE_RAW;
    // onStart will now trigger a reauthorization
  }

  @Override
  public IBinder onBind(Intent intent) {
    if (DEBUG) Log.d(getClass().getName(), "YammerService.onBind");
    return mBinder;
  }

  @Override
  public boolean onUnbind(Intent intent) {
    if (DEBUG) Log.d(getClass().getName(), "YammerService.onUnbind");
    // Don't invoke onRebind, so return false
    return false;
  }

  private void sendBroadcast(String _intent) {
    sendBroadcast(new Intent(_intent));
  }

  private YammerProxy yammerProxy;

  private void resetYammerProxy() {
    this.yammerProxy = null;
  }
  private YammerProxy getYammerProxy() {
    if (null == this.yammerProxy) {
      this.yammerProxy = YammerProxy.getYammerProxy(getApplicationContext());
    }
    return this.yammerProxy;
  }

  //TODO: Refactor these statics to instance methods
  public static void setAuthorized(boolean authorized) {
    YammerService.authorized = authorized;
  }

  public static boolean isAuthorized() {
    return authorized;
  }

  private YammerData yammerData = null;
  //TODO: privatize
  YammerData getYammerData() {
    if(null == yammerData) {
      yammerData = new YammerData(this);
    }
    return yammerData; 
  }

  private long currentNetworkId = 0L;
  
  private void setCurrentNetworkId(long _id) {
    currentNetworkId = _id;
    currentNetwork = null;
    getSettings().setCurrentNetworkId(_id);
    getYammerProxy().setCurrentNetwork(getCurrentNetwork());
  }
 
  //TODO: privatize
  long getCurrentNetworkId() {
    if(0L == currentNetworkId) { 
      currentNetworkId = getSettings().getCurrentNetworkId();
    }
    return currentNetworkId;
  }

  private Network currentNetwork;
  private Network getCurrentNetwork() {
    if(null == currentNetwork) { 
      currentNetwork = getYammerData().getNetwork(getCurrentNetworkId());
    }
    return currentNetwork;
  }

  //TODO: privatize
  long getCurrentUserId() {
    return getCurrentNetwork().userId;
  }

  private void toastUser(final int _resId, final Object... _args) {
    this.mHandler.post(new Runnable() {
      public void run() {
        Toast.makeText(getApplicationContext(), String.format(getText(_resId).toString(), _args), Toast.LENGTH_LONG).show();
      }
    });
  }
   
}
