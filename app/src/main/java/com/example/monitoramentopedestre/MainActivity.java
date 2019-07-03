package com.example.monitoramentopedestre;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.sql.Time;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import br.ufma.lsdi.cddl.CDDL;
import br.ufma.lsdi.cddl.Connection;
import br.ufma.lsdi.cddl.ConnectionFactory;
import br.ufma.lsdi.cddl.message.Message;
import br.ufma.lsdi.cddl.message.SensorDataMessage;
import br.ufma.lsdi.cddl.pubsub.Publisher;
import br.ufma.lsdi.cddl.pubsub.PublisherFactory;
import br.ufma.lsdi.cddl.pubsub.Subscriber;
import br.ufma.lsdi.cddl.pubsub.SubscriberFactory;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final double EARTH_RADIUS = 6371.00;
    private LocationManager locManager;
    private boolean gps_enabled = false;
    private boolean network_enabled = false;
    double lat_old = 0.0;
    double lon_old = 0.0;
    double lat_new;
    double lon_new;
    double time = 1;
    double speed = 0.0;

    private ListView listView;
    private ArrayList<String> arrayList = new ArrayList<String>();
    private ArrayAdapter<String> listViewAdapter;

    private List<String> listViewMessages;

    private CDDL cddl;
    private String email = "luis.silva@lsdi.ufma.br";

    private static final int MY_REQUEST_INT = 177;

    Publisher publisher;

    String acelerometro;
    String giroscopio;
    String location;

    EventBus eb;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        eb = EventBus.builder().build();
        eb.register(this);

        if (savedInstanceState == null) {
            configCDDL();
        }

        //permissao pra usar localizacao
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // se nao foi garantido
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, MY_REQUEST_INT);
            }
            return;
        }

        // inicializa o location manager para utilizarmos dados de localizacao localmente para o calculo de velocidade
        locManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        String sm = Context.SENSOR_SERVICE;
        SensorManager sensorManager = (SensorManager) getSystemService(sm);

        location = LocationManager.KEY_LOCATION_CHANGED;

        configStartButton();
        configStopButton();
        configListView();
    }

    private void configCDDL() {
        Connection c = ConnectionFactory.createConnection();
//        c.setHost(Connection.DEFAULT_HOST); tentamos conectar ao broker lsdi porem a subscricao nao funciona, alem da necessidade de estar conectado a rede local LSDI
//        c.setPort(Connection.DEFAULT_PORT);
        c.setHost("postman.cloudmqtt.com");
        c.setUsername("gnoyhyfh");
        c.setPassword("1fcYKehDxWjH");
        c.setPort("14733");
        c.setClientId(email);
        c.connect();

        cddl = CDDL.getInstance();
        cddl.setConnection(c);
        cddl.setContext(this);
        cddl.startService();
        cddl.startCommunicationTechnology(CDDL.INTERNAL_TECHNOLOGY_ID);

    }

    private void onMessage(Message message) {
        eb.post(new MessageEvent(message));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void on(MessageEvent event) {
        Object[] valor = event.getMessage().getServiceValue();
        listViewMessages.add(0, StringUtils.join(valor, ", "));
        //listViewAdapter.notifyDataSetChanged();
    }

    private void configStartButton() {
        Button button = findViewById(R.id.btn_iniciar);

        button.setOnClickListener(e -> {
            cddl.startLocationSensor(); // inicializa o sensor de localizacao e começa a publicar

            cddl.setFilter("SELECT * FROM SensorDataMessage where (serviceName = 'Location' AND accuracy <= 18) OR serviceName = 'Velocidade' "); //filtragem EPL


            // check de permissao para utilizacao de localizacao
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);

            this.onLocationChanged(null);

            listViewAdapter.add(location); // listagem de sensores

            cddl.startService();
        });
    }


    private void configStopButton() {
        Button button = findViewById(R.id.btn_parar);

        button.setOnClickListener(e -> {
            cddl.stopLocationSensor(); // para o sensor de localizacao via cddl

            locManager.removeUpdates(this); //desligando a utilizacao da localizacao localmente

            listViewAdapter.remove(location); //remove da listagem de sensores

            cddl.stopService();
        });
    }

    private void configListView() {
        listView = findViewById(R.id.lst_lista);
        listViewAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, arrayList);
        listView.setAdapter(listViewAdapter);
    }

    @Override
    public void onLocationChanged(Location location) {
        // calculo de velocidade e ajuste de elementos visuais baseados no seu valor
        if (location != null) {
            lat_new = location.getLongitude();
            lon_new = location.getLatitude();
            double distance = CalculationByDistance(lat_new, lon_new, lat_old, lon_old);

            speed = distance / time;

            lat_old = lat_new;
            lon_old = lon_new;

            TextView label = findViewById(R.id.txt_label);

            if(speed <= 0.45){
                label.setText(new DecimalFormat("#.####").format(speed) + " m/s" + " :: PARADO");
                label.setTextColor(Color.GREEN);
            } else if (speed > 2.1){
                label.setText(new DecimalFormat("#.####").format(speed) + " m/s" + " :: CORRENDO");
                label.setTextColor(Color.RED);
            } else {
                label.setText(new DecimalFormat("#.####").format(speed) + " m/s" + " :: CAMINHANDO");
                label.setTextColor(Color.YELLOW);
            }

            // criacao de publisher
            publisher = PublisherFactory.createPublisher();
            publisher.addConnection(cddl.getConnection());

            // publicacao do topico VELOCIDADE
            SensorDataMessage cim = new SensorDataMessage();
            cim.setServiceName("Velocidade");
            cim.setServiceValue(speed);
            cim.setMeasurementInterval((long) 5000);
            cim.setQos(1);
            publisher.publish(cim);
        }
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    @Override
    public void onProviderEnabled(String s) {

    }

    @Override
    public void onProviderDisabled(String s) {

    }

    public void onDestroy() {

        super.onDestroy();

    }

    // calculo de distancia utilizando a FORMULA DE HAVERSINE
    public double CalculationByDistance(double lat1, double lon1, double lat2, double lon2) {
        double Radius = EARTH_RADIUS;
        double dLat = Math.toRadians(lat2-lat1);
        double dLon = Math.toRadians(lon2-lon1);

        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);

        double a = haversin(dLat) + Math.cos(lat1) * Math.cos(lat2) * haversin(dLon);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return (Radius * c) * 1000;
    }

    // funcao da esquacao de HAVERSINE
    public double haversin(double val){
        return Math.pow(Math.sin(val/2), 2);
    }

}

