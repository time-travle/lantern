package org.lantern.state;

import java.util.Timer;
import java.util.TimerTask;

import org.cometd.annotation.Configure;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerSession;
import org.lantern.LanternClientConstants;
import org.lantern.LanternService;
import org.lantern.annotation.Keep;
import org.lantern.event.Events;
import org.lantern.event.SyncEvent;
import org.lantern.event.SyncType;
import org.lantern.event.UiLoadedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Service for pushing updated Lantern state to the client.
 */
@Service("sync")
@Singleton
@Keep
public class SyncService implements LanternService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Session
    private ServerSession session;

    private final SyncStrategy strategy;

    private final Model model;

    private final Timer timer;

    private boolean clientSynced;
    
    private String browserType;

    /**
     * Creates a new sync service.
     *
     * @param strategy The strategy to use for syncing
     * @param model The model to use.
     */
    @Inject
    public SyncService(final SyncStrategy strategy,
        final Model model, final Timer timer) {
        this.strategy = strategy;
        this.model = model;
        this.timer = timer;
        this.clientSynced = false;
        this.browserType = "unknown";
        // Make sure the config class is added as a listener before this class.
        Events.register(this);
    }


    @Override
    public void start() {
        if (timer != null) {
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    //sync();
                    delegateSync(SyncType.ADD, SyncPath.PEERS,
                        model.getPeers());
                    delegateSync(SyncType.ADD, SyncPath.INSTANCE_STATS,
                            model.getInstanceStats());
                }
            }, 3000, LanternClientConstants.SYNC_INTERVAL_SECONDS * 1000);
        }
    }

    @Override
    public void stop() {
        if (this.timer != null) {
            this.timer.cancel();
        }
    }

    public void sendClientPing() {
        delegateSync(SyncType.PING, SyncPath.PING, "");
        log.debug("Sending ping to client");
    }

    public boolean clientSynced() {
        return clientSynced;
    }

    public void setBrowserType(String browserType) {
        this.browserType = browserType;
    }

    public String getBrowserType() {
        return this.browserType;
    }

    @Configure("/service/sync")
    private void configureSync(final ConfigurableServerChannel channel) {
        channel.setPersistent(true);
    }

    //@Listener(Channel.META_CONNECT)
    @Listener(Channel.META_SUBSCRIBE)
    public void metaConnect(final ServerSession remote, final Message connect) {
        // Make sure we give clients the most recent data whenever they connect.
        log.debug("Got connection from client, calling sync");

        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                log.info("Syncing with frontend...");
                delegateSync(SyncPath.ALL, model);
                Events.asyncEventBus().post(new UiLoadedEvent());
                clientSynced = true;
                /* when the server receives a disconnect message
                 * from the client or the session expires
                 * the rmeove listener is triggered
                 */
                remote.addListener(
                    new ServerSession.RemoveListener() {
                        public void removed(ServerSession session, 
                            boolean timeout) {
                            clientSynced = false;
                            log.info("Frontend disconnected...");
                        }
                });
            }
        }, "CometD-Sync-OnConnect-Thread");
        t.setDaemon(true);
        t.start();

    }

    @Subscribe
    public void onSync(final SyncEvent syncEvent) {
        log.debug("Got sync event");
        // We want to force a sync here regardless of whether or not we've
        // recently synced.
        //sync(true, syncEvent.getChannel());
        delegateSync(syncEvent.getOp(), syncEvent.getPath(), syncEvent.getValue());
    }

    private void delegateSync(final SyncType type, final SyncPath path,
            final Object value) {
        delegateSync(type, path.getPath(), value);
    }
    
    private void delegateSync(final SyncPath path, final Object value) {
        delegateSync(SyncType.ADD, path.getPath(), value);
    }

    private void delegateSync(final SyncType syncType, final String path, 
        final Object value) {
        this.strategy.sync(this.session, syncType, path, value);
    }
}