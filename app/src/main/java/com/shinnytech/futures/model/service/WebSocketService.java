package com.shinnytech.futures.model.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketExtension;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.neovisionaries.ws.client.WebSocketState;
import com.shinnytech.futures.R;
import com.shinnytech.futures.application.BaseApplication;
import com.shinnytech.futures.constants.CommonConstants;
import com.shinnytech.futures.model.amplitude.api.Amplitude;
import com.shinnytech.futures.model.bean.accountinfobean.BrokerEntity;
import com.shinnytech.futures.model.bean.accountinfobean.OrderEntity;
import com.shinnytech.futures.model.bean.accountinfobean.UserEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqCancelOrderEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqConfirmSettlementEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqInsertOrderEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqLoginEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqPasswordEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqPeekMessageEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqSetChartEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqSetChartKlineEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqSubscribeQuoteEntity;
import com.shinnytech.futures.model.bean.reqbean.ReqTransferEntity;
import com.shinnytech.futures.model.engine.DataManager;
import com.shinnytech.futures.model.engine.LatestFileManager;
import com.shinnytech.futures.model.receiver.NotificationClickReceiver;
import com.shinnytech.futures.utils.LogUtils;
import com.shinnytech.futures.utils.SPUtils;
import com.shinnytech.futures.utils.TimeUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import static com.shinnytech.futures.constants.CommonConstants.AMP_AUTO;
import static com.shinnytech.futures.constants.CommonConstants.AMP_CANCEL_ORDER;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_DIRECTION;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_INSTRUMENT_ID;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_LOGIN_BROKER_ID;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_LOGIN_TIME;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_LOGIN_TYPE;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_LOGIN_TYPE_VALUE_AUTO;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_LOGIN_TYPE_VALUE_LOGIN;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_LOGIN_TYPE_VALUE_VISIT;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_LOGIN_USER_ID;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_OFFSET;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_PRICE;
import static com.shinnytech.futures.constants.CommonConstants.AMP_EVENT_VOLUME;
import static com.shinnytech.futures.constants.CommonConstants.AMP_INSERT_ORDER;
import static com.shinnytech.futures.constants.CommonConstants.AMP_VISIT;
import static com.shinnytech.futures.constants.CommonConstants.BROKER_ID_VISITOR;
import static com.shinnytech.futures.constants.CommonConstants.CHART_ID;
import static com.shinnytech.futures.constants.CommonConstants.CONFIG_SYSTEM_INFO;
import static com.shinnytech.futures.constants.CommonConstants.MD_OFFLINE;
import static com.shinnytech.futures.constants.CommonConstants.MD_TIMEOUT;
import static com.shinnytech.futures.constants.CommonConstants.TD_MESSAGE_BROKER_INFO;
import static com.shinnytech.futures.constants.CommonConstants.TD_MESSAGE_LOGIN_FAIL;
import static com.shinnytech.futures.constants.CommonConstants.TD_OFFLINE;
import static com.shinnytech.futures.constants.CommonConstants.TD_ONLINE;
import static com.shinnytech.futures.constants.CommonConstants.TD_TIMEOUT;

/**
 * date: 7/9/17
 * author: chenli
 * description: webSocket后台服务，分别控制与行情服务器和交易服务器的连接
 * version:
 * state: done
 */
public class WebSocketService extends Service {

    /**
     * date: 7/9/17
     * description: 行情广播类型
     */
    public static final String MD_BROADCAST = "MD_BROADCAST";

    /**
     * date: 7/9/17
     * description: 交易广播类型
     */
    public static final String TD_BROADCAST = "TD_BROADCAST";

    /**
     * date: 7/9/17
     * description: 行情广播信息
     */
    public static final String MD_BROADCAST_ACTION = WebSocketService.class.getName() + "." + MD_BROADCAST;

    /**
     * date: 7/9/17
     * description: 交易广播信息
     */
    public static final String TD_BROADCAST_ACTION = WebSocketService.class.getName() + "." + TD_BROADCAST;

    private static final int TIMEOUT = 5000;
    private final IBinder mBinder = new LocalBinder();
    private WebSocket mWebSocketClientMD;

    private WebSocket mWebSocketClientTD;

    private DataManager sDataManager = DataManager.getInstance();

    private LocalBroadcastManager mLocalBroadcastManager = LocalBroadcastManager.getInstance(this);

    private long mMDLastPong = System.currentTimeMillis() / 1000;

    private long mTDLastPong = System.currentTimeMillis() / 1000;

    public WebSocketService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String NOTIFICATION_CHANNEL_ID = "com.shinnytech.futures";
            String channelName = "WebSocketService";
            NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
            chan.setLightColor(Color.BLUE);
            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            assert manager != null;
            manager.createNotificationChannel(chan);
            Intent intent = new Intent(this, NotificationClickReceiver.class);
            intent.setAction("notification_click");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
            Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("快期小Q下单软件正在运行")
                    .setContentText("点击返回程序")
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                    .setContentIntent(pendingIntent)
                    .build();
            startForeground(1, notification);
        } else {
            Intent intent = new Intent(this, NotificationClickReceiver.class);
            intent.setAction("notification_click");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
            Notification notification = new NotificationCompat.Builder(this, "service")
                    .setContentTitle("快期小Q下单软件正在运行")
                    .setContentText("点击返回程序")
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                    .setContentIntent(pendingIntent)
                    .build();
            startForeground(1, notification);
        }


        Timer timer = new Timer();
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {

                if ((System.currentTimeMillis() / 1000 - mMDLastPong) >= 7) {
                    sendMessage(MD_OFFLINE, MD_BROADCAST);
                } else {
                    if (mWebSocketClientMD != null) mWebSocketClientMD.sendPing();
                }

                if ((System.currentTimeMillis() / 1000 - mTDLastPong) >= 7) {
                    sendMessage(TD_OFFLINE, TD_BROADCAST);
                } else {
                    if (mWebSocketClientTD != null) mWebSocketClientTD.sendPing();
                }

            }
        };
        timer.schedule(timerTask, 5000, 5000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    public void sendMessage(String message, String type) {
        switch (type) {
            case MD_BROADCAST:
                Intent intent = new Intent(MD_BROADCAST_ACTION);
                intent.putExtra("msg", message);
                mLocalBroadcastManager.sendBroadcast(intent);
                break;
            case TD_BROADCAST:
                Intent intentTransaction = new Intent(TD_BROADCAST_ACTION);
                intentTransaction.putExtra("msg", message);
                mLocalBroadcastManager.sendBroadcast(intentTransaction);
                break;
        }

    }

    /**
     * date: 6/28/17
     * author: chenli
     * description: 连接行情服务器
     */
    public void connectMD(String url) {
        try {
            mWebSocketClientMD = new WebSocketFactory()
                    .setConnectionTimeout(TIMEOUT)
                    .createSocket(url)
                    .addListener(new WebSocketAdapter() {

                        // A text message arrived from the server.
                        public void onTextMessage(WebSocket websocket, String message) {
                            LogUtils.e(message, false);
                            try {
                                JSONObject jsonObject = new JSONObject(message);
                                String aid = jsonObject.getString("aid");
                                switch (aid) {
                                    case "rsp_login":
                                        mWebSocketClientMD.sendPing();
//                                        sendSubscribeAfterConnect();
                                        break;
                                    case "rtn_data":
                                        BaseApplication.setsIndex(0);
                                        sDataManager.refreshFutureBean(jsonObject);
                                        break;
                                    default:
                                        return;
                                }
                                if (!BaseApplication.ismBackGround()) sendPeekMessage();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onPongFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
                            super.onPongFrame(websocket, frame);
                            mMDLastPong = System.currentTimeMillis() / 1000;
                            LogUtils.e("MDPong", true);
                        }

                    })
                    .addHeader("User-Agent", sDataManager.USER_AGENT + " " + sDataManager.APP_VERSION)
                    .addHeader("SA-Machine", Amplitude.getInstance().getDeviceId())
                    .addHeader("SA-Session", Amplitude.getInstance().getDeviceId())
                    .addExtension(WebSocketExtension.PERMESSAGE_DEFLATE)
                    .connectAsynchronously();
            int index = BaseApplication.getsIndex() + 1;
            if (index == BaseApplication.getsMDURLs().size()) index = 0;
            BaseApplication.setsIndex(index);
        } catch (Exception e) {
            sendMessage(MD_TIMEOUT, MD_BROADCAST);
            e.printStackTrace();
        }

    }

    /**
     * date: 2019/3/17
     * author: chenli
     * description: 首次连接行情服务器与断开重连的行情订阅处理
     */
    private void sendSubscribeAfterConnect() {
        if (!sDataManager.QUOTES.isEmpty() && mWebSocketClientMD != null) {
            mWebSocketClientMD.sendText(sDataManager.QUOTES);
        } else {
            List<String> list = LatestFileManager.getCombineInsList(
                    new ArrayList<>(LatestFileManager.getOptionalInsList().keySet()));
            //推荐列表刷新
            list.addAll(LatestFileManager.getMainInsList().keySet());
            sendSubscribeQuote(TextUtils.join(",", list));
        }

        if (!sDataManager.CHARTS.isEmpty() && mWebSocketClientMD != null) {
            mWebSocketClientMD.sendText(sDataManager.CHARTS);
        }
    }

    public boolean isMDConnected() {
        if (mWebSocketClientMD != null) {
            return mWebSocketClientMD.isOpen();
        } else {
            return false;
        }
    }

    public void reConnectMD(String url) {
        disConnectMD();
        connectMD(url);
    }

    public void disConnectMD() {
        if (mWebSocketClientMD != null && mWebSocketClientMD.getState() == WebSocketState.OPEN) {
            mWebSocketClientMD.disconnect();
            mWebSocketClientMD = null;
        }
    }

    /**
     * date: 7/9/17
     * author: chenli
     * description: 行情订阅
     */
    public void sendSubscribeQuote(String insList) {
        if (mWebSocketClientMD != null && mWebSocketClientMD.getState() == WebSocketState.OPEN) {
            ReqSubscribeQuoteEntity reqSubscribeQuoteEntity = new ReqSubscribeQuoteEntity();
            reqSubscribeQuoteEntity.setAid("subscribe_quote");
            reqSubscribeQuoteEntity.setIns_list(insList);
            String subScribeQuote = new Gson().toJson(reqSubscribeQuoteEntity);
            mWebSocketClientMD.sendText(subScribeQuote);
            sDataManager.QUOTES = subScribeQuote;
            LogUtils.e(subScribeQuote, true);
        }
    }

    /**
     * date: 7/9/17
     * author: chenli
     * description: 获取合约信息
     */
    public void sendPeekMessage() {
        if (mWebSocketClientMD != null && mWebSocketClientMD.getState() == WebSocketState.OPEN) {
            ReqPeekMessageEntity reqPeekMessageEntity = new ReqPeekMessageEntity();
            reqPeekMessageEntity.setAid("peek_message");
            String peekMessage = new Gson().toJson(reqPeekMessageEntity);
            mWebSocketClientMD.sendText(peekMessage);
            LogUtils.e(peekMessage, false);
        }
    }

    /**
     * date: 7/9/17
     * author: chenli
     * description: 分时图
     */
    public void sendSetChart(String ins_list) {
        if (mWebSocketClientMD != null && mWebSocketClientMD.getState() == WebSocketState.OPEN) {
            ReqSetChartEntity reqSetChartEntity = new ReqSetChartEntity();
            reqSetChartEntity.setAid("set_chart");
            reqSetChartEntity.setChart_id(CHART_ID);
            reqSetChartEntity.setIns_list(ins_list);
            reqSetChartEntity.setDuration(60000000000l);
            reqSetChartEntity.setTrading_day_start(0);
            reqSetChartEntity.setTrading_day_count(86400000000000l);
            String setChart = new Gson().toJson(reqSetChartEntity);
            sDataManager.CHARTS = setChart;
            mWebSocketClientMD.sendText(setChart);
            LogUtils.e(setChart, true);
        }
    }

    /**
     * date: 2018/12/14
     * author: chenli
     * description: k线图
     */
    public void sendSetChartKline(String ins_list, int view_width, String duration) {
        if (mWebSocketClientMD != null && mWebSocketClientMD.getState() == WebSocketState.OPEN) {
            try {
                long duration_l = Long.parseLong(duration);
                ReqSetChartKlineEntity reqSetChartKlineEntity = new ReqSetChartKlineEntity();
                reqSetChartKlineEntity.setAid("set_chart");
                reqSetChartKlineEntity.setChart_id(CHART_ID);
                reqSetChartKlineEntity.setIns_list(ins_list);
                reqSetChartKlineEntity.setView_width(view_width);
                reqSetChartKlineEntity.setDuration(duration_l);
                String setChart = new Gson().toJson(reqSetChartKlineEntity);
                sDataManager.CHARTS = setChart;
                mWebSocketClientMD.sendText(setChart);
                LogUtils.e(setChart, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * date: 6/28/17
     * author: chenli
     * description: 连接交易服务器
     */
    public void connectTD() {
        try {
            mWebSocketClientTD = new WebSocketFactory()
                    .setConnectionTimeout(TIMEOUT)
                    .createSocket(CommonConstants.TRANSACTION_URL)
                    .addListener(new WebSocketAdapter() {
                        @Override

                        // A text message arrived from the server.
                        public void onTextMessage(final WebSocket websocket, String message) {
                            LogUtils.e(message, false);
                            try {
                                JSONObject jsonObject = new JSONObject(message);
                                String aid = jsonObject.getString("aid");
                                switch (aid) {
                                    case "rtn_brokers":
                                        sendMessage(TD_ONLINE, TD_BROADCAST);
                                        mWebSocketClientTD.sendPing();
                                        loginConfig(message);
                                        break;
                                    case "rtn_data":
                                        sDataManager.refreshTradeBean(jsonObject);
                                        break;
                                    default:
                                        return;
                                }
                                if (!BaseApplication.ismBackGround()) sendPeekMessageTransaction();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onPongFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
                            super.onPongFrame(websocket, frame);
                            mTDLastPong = System.currentTimeMillis() / 1000;
                            LogUtils.e("TDPong", true);
                        }

                    })
                    .addExtension(WebSocketExtension.PERMESSAGE_DEFLATE)
                    .addHeader("User-Agent", sDataManager.USER_AGENT + " " + sDataManager.APP_VERSION)
                    .addHeader("SA-Machine", Amplitude.getInstance().getDeviceId())
                    .addHeader("SA-Session", Amplitude.getInstance().getDeviceId())
                    .connectAsynchronously();
        } catch (Exception e) {
            sendMessage(TD_TIMEOUT, TD_BROADCAST);
            e.printStackTrace();
        }

    }

    /**
     * date: 2019/3/17
     * author: chenli
     * description: 登录设置，自动登录
     */
    private void loginConfig(String message) {
        BrokerEntity brokerInfo = new Gson().fromJson(message, BrokerEntity.class);
        sDataManager.getBroker().setBrokers(brokerInfo.getBrokers());
        sendMessage(TD_MESSAGE_BROKER_INFO, TD_BROADCAST);

        Context context = BaseApplication.getContext();
        if (SPUtils.contains(context, CommonConstants.CONFIG_LOGIN_DATE)) {
            String date = (String) SPUtils.get(context, CommonConstants.CONFIG_LOGIN_DATE, "");
            if (date.isEmpty()) return;
            String name = (String) SPUtils.get(context, CommonConstants.CONFIG_ACCOUNT, "");
            String password = (String) SPUtils.get(context, CommonConstants.CONFIG_PASSWORD, "");
            String broker = (String) SPUtils.get(context, CommonConstants.CONFIG_BROKER, "");
            boolean isPermissionDenied = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED;
            if ((name != null && name.contains(BROKER_ID_VISITOR) && !TimeUtils.getNowTime().equals(date)) || isPermissionDenied){
                sendMessage(TD_MESSAGE_LOGIN_FAIL, TD_BROADCAST);
                return;
            }

            sDataManager.LOGIN_BROKER_ID = broker;
            sDataManager.LOGIN_USER_ID = name;
            sDataManager.LOGIN_TYPE = AMP_EVENT_LOGIN_TYPE_VALUE_AUTO;
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put(AMP_EVENT_LOGIN_BROKER_ID, broker);
                jsonObject.put(AMP_EVENT_LOGIN_USER_ID, name);
                jsonObject.put(AMP_EVENT_LOGIN_TIME, System.currentTimeMillis());
                jsonObject.put(AMP_EVENT_LOGIN_TYPE, AMP_EVENT_LOGIN_TYPE_VALUE_AUTO);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            Amplitude.getInstance().logEvent(AMP_AUTO, jsonObject);
            sendReqLogin(broker, name, password);
        }

    }

    public boolean isTDConnected() {
        if (mWebSocketClientTD != null) {
            return mWebSocketClientTD.isOpen();
        } else {
            return false;
        }
    }

    public void reConnectTD() {
        disConnectTD();
        connectTD();
    }

    public void disConnectTD() {
        if (mWebSocketClientTD != null && mWebSocketClientTD.getState() == WebSocketState.OPEN) {
            mWebSocketClientTD.disconnect();
            DataManager.getInstance().clearAccount();
        }
    }

    /**
     * date: 7/9/17
     * author: chenli
     * description: 获取合约信息
     */
    public void sendPeekMessageTransaction() {
        if (mWebSocketClientTD != null && mWebSocketClientTD.getState() == WebSocketState.OPEN) {
            ReqPeekMessageEntity reqPeekMessageEntity = new ReqPeekMessageEntity();
            reqPeekMessageEntity.setAid("peek_message");
            String peekMessage = new Gson().toJson(reqPeekMessageEntity);
            mWebSocketClientTD.sendText(peekMessage);
            LogUtils.e(peekMessage, false);
        }
    }

    /**
     * date: 7/9/17
     * author: chenli
     * description: 用户登录
     */
    public void sendReqLogin(String bid, String user_name, String password) {
        if (mWebSocketClientTD != null && mWebSocketClientTD.getState() == WebSocketState.OPEN) {
            String systemInfo = (String) SPUtils.get(BaseApplication.getContext(), CONFIG_SYSTEM_INFO, "");
            ReqLoginEntity reqLoginEntity = new ReqLoginEntity();
            reqLoginEntity.setAid("req_login");
            reqLoginEntity.setBid(bid);
            reqLoginEntity.setUser_name(user_name);
            reqLoginEntity.setPassword(password);
            reqLoginEntity.setClient_system_info(systemInfo);
            reqLoginEntity.setClient_app_id("SHINNY_XQ_1.0");
            String reqLogin = new Gson().toJson(reqLoginEntity);
            mWebSocketClientTD.sendText(reqLogin);
            LogUtils.e(reqLogin, true);
            LatestFileManager.insertLogToDB(reqLogin);
        }
    }

    /**
     * date: 7/9/17
     * author: chenli
     * description: 确认结算单
     */
    public void sendReqConfirmSettlement() {
        if (mWebSocketClientTD != null && mWebSocketClientTD.getState() == WebSocketState.OPEN) {
            ReqConfirmSettlementEntity reqConfirmSettlementEntity = new ReqConfirmSettlementEntity();
            reqConfirmSettlementEntity.setAid("confirm_settlement");
            String confirmSettlement = new Gson().toJson(reqConfirmSettlementEntity);
            mWebSocketClientTD.sendText(confirmSettlement);
            LogUtils.e(confirmSettlement, true);
            LatestFileManager.insertLogToDB(confirmSettlement);
        }
    }


    /**
     * date: 7/9/17
     * author: chenli
     * description: 下单
     */
    public void sendReqInsertOrder(String exchange_id, String instrument_id, String direction,
                                   String offset, int volume, String price_type, double price) {
        if (mWebSocketClientTD != null && mWebSocketClientTD.getState() == WebSocketState.OPEN) {
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put(AMP_EVENT_PRICE, price);
                jsonObject.put(AMP_EVENT_INSTRUMENT_ID, exchange_id + "." + instrument_id);
                jsonObject.put(AMP_EVENT_VOLUME, volume);
                jsonObject.put(AMP_EVENT_DIRECTION, direction);
                jsonObject.put(AMP_EVENT_OFFSET, offset);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            Amplitude.getInstance().logEvent(AMP_INSERT_ORDER, jsonObject);
            String user_id = DataManager.getInstance().USER_ID;
            ReqInsertOrderEntity reqInsertOrderEntity = new ReqInsertOrderEntity();
            reqInsertOrderEntity.setAid("insert_order");
            reqInsertOrderEntity.setUser_id(user_id);
            reqInsertOrderEntity.setOrder_id("");
            reqInsertOrderEntity.setExchange_id(exchange_id);
            reqInsertOrderEntity.setInstrument_id(instrument_id);
            reqInsertOrderEntity.setDirection(direction);
            reqInsertOrderEntity.setOffset(offset);
            reqInsertOrderEntity.setVolume(volume);
            reqInsertOrderEntity.setPrice_type(price_type);
            reqInsertOrderEntity.setLimit_price(price);
            reqInsertOrderEntity.setVolume_condition("ANY");
            reqInsertOrderEntity.setTime_condition("GFD");
            String reqInsertOrder = new Gson().toJson(reqInsertOrderEntity);
            mWebSocketClientTD.sendText(reqInsertOrder);
            LogUtils.e(reqInsertOrder, true);
            LatestFileManager.insertLogToDB(reqInsertOrder);
        }
    }

    /**
     * date: 7/9/17
     * author: chenli
     * description: 撤单
     */
    public void sendReqCancelOrder(String order_id) {
        if (mWebSocketClientTD != null && mWebSocketClientTD.getState() == WebSocketState.OPEN) {
            String user_id = DataManager.getInstance().USER_ID;
            UserEntity userEntity = sDataManager.getTradeBean().getUsers().get(user_id);
            if (userEntity != null) {
                OrderEntity orderEntity = userEntity.getOrders().get(order_id);
                if (orderEntity != null) {
                    JSONObject jsonObject = new JSONObject();
                    try {
                        jsonObject.put(AMP_EVENT_PRICE, orderEntity.getLimit_price());
                        jsonObject.put(AMP_EVENT_INSTRUMENT_ID, orderEntity.getExchange_id() + "." + orderEntity.getInstrument_id());
                        jsonObject.put(AMP_EVENT_VOLUME, orderEntity.getVolume_left());
                        jsonObject.put(AMP_EVENT_DIRECTION, orderEntity.getDirection());
                        jsonObject.put(AMP_EVENT_OFFSET, orderEntity.getOffset());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    Amplitude.getInstance().logEvent(AMP_CANCEL_ORDER, jsonObject);
                }
            }
            ReqCancelOrderEntity reqCancelOrderEntity = new ReqCancelOrderEntity();
            reqCancelOrderEntity.setAid("cancel_order");
            reqCancelOrderEntity.setUser_id(user_id);
            reqCancelOrderEntity.setOrder_id(order_id);
            String reqInsertOrder = new Gson().toJson(reqCancelOrderEntity);
            mWebSocketClientTD.sendText(reqInsertOrder);
            LogUtils.e(reqInsertOrder, true);
            LatestFileManager.insertLogToDB(reqInsertOrder);
        }
    }

    /**
     * date: 7/9/17
     * author: chenli
     * description: 银期转帐
     */
    public void sendReqTransfer(String future_account, String future_password, String bank_id,
                                String bank_password, String currency, float amount) {
        if (mWebSocketClientTD != null && mWebSocketClientTD.getState() == WebSocketState.OPEN) {
            ReqTransferEntity reqTransferEntity = new ReqTransferEntity();
            reqTransferEntity.setAid("req_transfer");
            reqTransferEntity.setFuture_account(future_account);
            reqTransferEntity.setFuture_password(future_password);
            reqTransferEntity.setBank_id(bank_id);
            reqTransferEntity.setBank_password(bank_password);
            reqTransferEntity.setCurrency(currency);
            reqTransferEntity.setAmount(amount);
            String reqTransfer = new Gson().toJson(reqTransferEntity);
            mWebSocketClientTD.sendText(reqTransfer);
            LogUtils.e(reqTransfer, true);
            LatestFileManager.insertLogToDB(reqTransfer);
        }
    }

    /**
     * date: 2019/1/3
     * author: chenli
     * description: 修改密码
     */
    public void sendReqPassword(String new_password, String old_password) {
        if (mWebSocketClientTD != null && mWebSocketClientTD.getState() == WebSocketState.OPEN) {
            ReqPasswordEntity reqPasswordEntity = new ReqPasswordEntity();
            reqPasswordEntity.setAid("change_password");
            reqPasswordEntity.setNew_password(new_password);
            reqPasswordEntity.setOld_password(old_password);
            String reqPassword = new Gson().toJson(reqPasswordEntity);
            mWebSocketClientTD.sendText(reqPassword);
            LogUtils.e(reqPassword, true);
            LatestFileManager.insertLogToDB(reqPassword);
        }
    }

    public class LocalBinder extends Binder {
        public WebSocketService getService() {
            return WebSocketService.this;
        }
    }
}
