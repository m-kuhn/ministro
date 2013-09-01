/*
    Copyright (c) 2011-20013, BogDan Vatra <bogdan@kde.org>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.kde.necessitas.ministro;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.SparseArray;

public class MinistroService extends Service
{
    public static final String TAG = "MinistroService";

    private static final String MINISTRO_CHECK_UPDATES_KEY = "LASTCHECK";
    private static final String MINISTRO_CHECK_FREQUENCY_KEY = "CHECKFREQUENCY";
    private static final String MINISTRO_REPOSITORY_KEY = "REPOSITORY";
    private static final String MINISTRO_MIGRATED_KEY = "MIGRATED";
    private static final String MINISTRO_DEFAULT_REPOSITORY = "stable";
    private static final String MINISTRO_SOURCES_KEY = "SOURCES";
    private static final String MINISTRO_CHECK_CRC_KEY = "CHECK_CRC";

    private HashMap<String, Integer> m_sources = new HashMap<String, Integer>();
    private String m_repository = null;
    private long m_lastCheckUpdates = 0;
    private long m_checkFrequency = 7l * 24 * 3600 * 1000; // 7 days
    private int m_nextId = 0;
    private String m_ministroRootPath = null;
    private boolean m_checkCrc = true;


    public String getRepository()
    {
        synchronized (this)
        {
            return m_repository;
        }
    }

    public boolean checkCrc()
    {
        return m_checkCrc;
    }

    public void setRepository(String value)
    {
        synchronized (this)
        {
            m_repository = value;
            m_lastCheckUpdates = 0;
            saveSettings();
        }
    }

    public Long getCheckFrequency()
    {
        synchronized (this)
        {
            return m_checkFrequency / (24 * 3600 * 1000);
        }
    }

    public void setCheckFrequency(long value)
    {
        synchronized (this)
        {
            m_checkFrequency = value * 24 * 3600 * 1000;
            m_lastCheckUpdates = 0;
            saveSettings();
        }
    }

    // MinistroService instance, its used by MinistroActivity to directly access
    // services data (e.g. libraries)
    private static MinistroService m_instance = null;

    public static MinistroService instance()
    {
        return m_instance;
    }

    public MinistroService()
    {
        m_instance = this;
    }

    private int m_actionId = 0; // last actions id
    private Handler m_handler = null;

    private SparseArray<Session> m_sessions = new SparseArray<Session>();

    class CheckForUpdates extends AsyncTask<Void, Void, Boolean>
    {
        private double getLocalVersion(Integer sourceId) throws Exception
        {
            File file = new File(getVersionXmlFile(sourceId, m_repository));
            if (!file.exists())
                return -1;

            DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentFactory.newDocumentBuilder();
            Document dom = documentBuilder.parse(new FileInputStream(file));
            Element root = dom.getDocumentElement();
            return Double.valueOf(root.getAttribute("version"));
        }

        private double getRemoteVersion(Integer sourceId) throws Exception
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document dom = null;
            Element root = null;
            URLConnection connection = getVersionsFileUrl(sourceId).openConnection();
            connection.setConnectTimeout(MinistroActivity.CONNECTION_TIMEOUT);
            connection.setReadTimeout(MinistroActivity.READ_TIMEOUT);
            dom = builder.parse(connection.getInputStream());
            root = dom.getDocumentElement();
            root.normalize();
            return Double.valueOf(root.getAttribute("latest"));
        }
        @Override
        protected Boolean doInBackground(Void... params)
        {
            boolean res = false;
            for (Integer sourceId : m_sources.values())
            {
                try
                {
                    double localVersion = getLocalVersion(sourceId);
                    if (localVersion > 0 && localVersion != getRemoteVersion(sourceId))
                        res = true;
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            return res;
        }

        @SuppressWarnings("deprecation")
        @Override
        protected void onPostExecute(Boolean result)
        {
            if (!result)
                return;

            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);

            int icon = R.drawable.icon;
            CharSequence tickerText = getResources().getString(R.string.new_qt_libs_msg); // ticker-text
            long when = System.currentTimeMillis(); // notification time
            Context context = getApplicationContext(); // application Context
            CharSequence contentTitle = getResources().getString(R.string.ministro_update_msg); // expanded message title
            CharSequence contentText = getResources().getString(R.string.new_qt_libs_tap_msg); // expanded message text

            Intent notificationIntent = new Intent(MinistroService.this,
                    MinistroActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(MinistroService.this, 0, notificationIntent, 0);

            // the next two lines initialize the Notification, using the configurations above
            Notification notification = new Notification(icon, tickerText, when);
            notification.setLatestEventInfo(context, contentTitle, contentText, contentIntent);
            notification.defaults |= Notification.DEFAULT_SOUND;
            notification.defaults |= Notification.DEFAULT_LIGHTS;
            try {
                nm.notify(1, notification);
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    public String getMinistroRootPath()
    {
        return m_ministroRootPath;
    }

    public String getMinistroSslRootPath()
    {
        return m_ministroRootPath + "dl/ssl/";
    }

    public String getMinistroStyleRootPath(int displayDpi)
    {
        if (displayDpi != -1)
            return m_ministroRootPath + "dl/style/" + displayDpi + "/";
        return m_ministroRootPath + "dl/style/";
    }

    public String getVersionXmlFile(Integer sourceId, String repository)
    {
        return m_ministroRootPath + "xml/" + sourceId + "_" + repository + ".xml";
    }

    public String getLibsRootPath(Integer sourceId, String repository)
    {
        return m_ministroRootPath + "dl/" + sourceId + "/" + repository + "/";
    }

    URL getVersionsFileUrl(Integer sourceId) throws MalformedURLException
    {
        return new URL(getSource(sourceId) + getRepository() + "/" + android.os.Build.CPU_ABI + "/android-" + android.os.Build.VERSION.SDK_INT + "/versions.xml");
    }

    URL getLibsXmlUrl(Integer sourceId, String version) throws MalformedURLException
    {
        return new URL(getSource(sourceId) + getRepository() + "/" + android.os.Build.CPU_ABI + "/android-" + android.os.Build.VERSION.SDK_INT + "/libs-" + version + ".xml");
    }


    public void createSourcePath(Integer sourceId, String repository)
    {
        Library.mkdirParents(m_ministroRootPath, "dl/" + sourceId+ "/" + repository, 0);
    }

    public Session getUpdateSession()
    {
        synchronized (this)
        {
            if (m_sessions.size() == 0)
            {
                Bundle params = new Bundle();
                params.putBoolean(Session.UPDATE_KEY, true);
                Session session = new Session(this, null, params);
                m_sessions.put(m_actionId++, session);
                return session;
            }
            return null;
        }
    }

    private void startActivity(boolean refreshLibs)
    {
        int id = m_sessions.keyAt(0);
        if (refreshLibs)
            getSession(id).refreshLibraries(false);
        final Intent intent = new Intent(MinistroService.this, MinistroActivity.class);
        intent.putExtra("id", id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean failed = false;
        try
        {
            m_handler.postDelayed(new Runnable()
            {
                public void run()
                {
                    MinistroService.this.startActivity(intent);
                }
            }, 100);
        }
        catch (Exception e)
        {
            failed = true;
            e.printStackTrace();
        }
        finally
        {
            // Removes the dead Activity from our list as it will never finish
            // by itself.
            if (failed)
                retrievalFinished(id, Session.Result.Canceled);
        }
    }

    private void showActivity()
    {
        Intent intent = new Intent(MinistroService.this, MinistroActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
    /**
    * Creates and sets up a {@link MinistroActivity} to retrieve the modules
    * specified in the <code>session</code> argument.
    *
    * @param session
    */

    public void startRetrieval(Session session)
    {
        synchronized (this)
        {
            int id = m_actionId++;
            boolean startActivity = m_sessions.size() == 0;
            m_sessions.put(id, session);
            if (startActivity)
                startActivity(false);
            else
                showActivity();
        }
    }

    public Session getSession(int id)
    {
        synchronized (this)
        {
            if (m_sessions.indexOfKey(id) >= 0)
                return m_sessions.get(id);
            return null;
        }
    }

    public SharedPreferences getPreferences()
    {
        return getSharedPreferences("Ministro", MODE_PRIVATE);
    }
    /**
    * Called by a finished {@link MinistroActivity} in order to let the service
    * notify the application which caused the activity about the result of the
    * retrieval.
    *
    * @param id
    */
    void retrievalFinished(int id, Session.Result res)
    {
        synchronized (this)
        {
            if (m_sessions.indexOfKey(id) >= 0)
            {
                m_sessions.get(id).retrievalFinished(res);
                m_sessions.remove(id);
                if (m_sessions.size() == 0)
                    m_actionId = 0;
                else
                    startActivity(true);
            }
        }
    }

    public Collection<Integer> getSourcesIds()
    {
        return m_sources.values();
    }

    public ArrayList<Integer> getSourcesIds(String[] sources)
    {
        ArrayList<Integer> ids = new ArrayList<Integer>();
        synchronized (this)
        {
            boolean saveSettings = false;
            for (String source : sources)
            {
                if (!source.endsWith("/"))
                    source += "/";
                if (!m_sources.containsKey(source))
                {
                    m_sources.put(source, m_nextId);
                    ids.add(m_nextId++);
                    saveSettings = true;
                }
                else
                    ids.add(m_sources.get(source));
            }
            if (saveSettings)
                saveSettings();
        }
        return ids;
    }

    public String getSource(Integer sourceId)
    {
        for (String source : m_sources.keySet())
        {
            if (m_sources.get(source) == sourceId)
                return source;
        }
        return null;
    }

    public void loadSettings()
    {
        synchronized (this)
        {
            try
            {
                @SuppressWarnings("resource")
                BufferedReader reader = new BufferedReader(new FileReader(getFilesDir().getAbsolutePath() + "/ministro_conf.json"));
                StringBuilder builder = new StringBuilder();
                String line = reader.readLine();
                while (line != null)
                {
                    builder.append(line);
                    builder.append("\n");
                    line = reader.readLine();
                }
                JSONObject json = new JSONObject(builder.toString());
                m_lastCheckUpdates = json.getLong(MINISTRO_CHECK_UPDATES_KEY);
                m_checkFrequency = json.getLong(MINISTRO_CHECK_FREQUENCY_KEY);
                m_repository = json.getString(MINISTRO_REPOSITORY_KEY);
                JSONArray sources = json.getJSONArray(MINISTRO_SOURCES_KEY);
                m_sources.clear();
                m_nextId = 0;
                for (int i = 0; i < sources.length(); i++)
                {
                    JSONObject s = sources.getJSONObject(i);
                    int id = s.getInt("id");
                    if (id >= m_nextId)
                        m_nextId = id + 1;
                    m_sources.put(s.getString("url"), id);
                    try
                    {
                        String path = getLibsRootPath(id, m_repository);
                        File f = new File(path + "style");
                        if (f.exists())
                        {
                            Library.removeAllFiles(path + "style");
                            f.delete();
                        }
                        f = new File(path + "ssl");
                        if (f.exists())
                        {
                            Library.removeAllFiles(path + "ssl");
                            f.delete();
                        }
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public void saveSettings()
    {
        synchronized (this)
        {
            try
            {
                JSONObject json = new JSONObject();
                json.put(MINISTRO_CHECK_UPDATES_KEY, m_lastCheckUpdates);
                json.put(MINISTRO_CHECK_FREQUENCY_KEY, m_checkFrequency);
                json.put(MINISTRO_REPOSITORY_KEY, m_repository);
                JSONArray sources = new JSONArray();
                for (String url : m_sources.keySet())
                {
                    JSONObject s = new JSONObject();
                    s.put("url", url);
                    s.put("id", m_sources.get(url));
                    sources.put(s);
                }
                json.put(MINISTRO_SOURCES_KEY, sources);
                OutputStreamWriter jsonWriter;
                jsonWriter = new OutputStreamWriter(new FileOutputStream(getFilesDir().getAbsolutePath() + "/ministro_conf.json"));
                jsonWriter.write(json.toString());
                jsonWriter.close();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private void migrateSettings()
    {
        try
        {
            // Migrate settings
            SharedPreferences preferences = getPreferences();
            m_repository = preferences.getString(MINISTRO_REPOSITORY_KEY, MINISTRO_DEFAULT_REPOSITORY);
            m_checkFrequency = preferences.getLong(MINISTRO_CHECK_FREQUENCY_KEY, 7l * 24 * 3600 * 1000);
            m_lastCheckUpdates = preferences.getLong(MINISTRO_CHECK_UPDATES_KEY, 0);//System.currentTimeMillis());
            SharedPreferences.Editor editor = preferences.edit();
            editor.remove(MINISTRO_REPOSITORY_KEY);
            editor.remove(MINISTRO_CHECK_FREQUENCY_KEY);
            editor.remove(MINISTRO_CHECK_UPDATES_KEY);
            editor.putBoolean(MINISTRO_MIGRATED_KEY, true);
            editor.commit();

            // Migrate content
            String rootPath = getFilesDir().getAbsolutePath() + "/";
            new File(rootPath + "xml/").mkdirs();
            if (new File(rootPath + "version.xml").exists())
            {
                m_sources.put(Session.NECESSITAS_SOURCE[0], m_nextId);
                new File(rootPath + "version.xml").renameTo(new File(rootPath + "xml/" + m_nextId + "_" + m_repository + ".xml"));
                Library.mkdirParents(rootPath, "dl/"+ m_nextId, 0);
                new File(rootPath + "qt").renameTo(new File(rootPath  + "dl/" + m_nextId + "/" + m_repository));
                m_nextId++;
            }
            saveSettings();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate()
    {
        m_handler = new Handler();
        try {
            m_ministroRootPath = getFilesDir().getCanonicalPath() + "/";
        } catch (IOException e) {
            e.printStackTrace();
        }
        SharedPreferences preferences = getPreferences();
        if (!preferences.getBoolean(MINISTRO_MIGRATED_KEY, false))
            migrateSettings();
        else
            loadSettings();

        m_checkCrc = preferences.getBoolean(MINISTRO_CHECK_CRC_KEY, true);
        if (!m_checkCrc)
        {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(MINISTRO_CHECK_CRC_KEY, true);
            editor.commit();
        }

        if (MinistroActivity.isOnline(this) && System.currentTimeMillis() - m_lastCheckUpdates > m_checkFrequency)
        {
            m_lastCheckUpdates = System.currentTimeMillis();
            saveSettings();
            new CheckForUpdates().execute((Void[])null);
        }
        super.onCreate();
    }

    @Override
    public void onDestroy()
    {
        SharedPreferences preferences = getPreferences();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(MINISTRO_CHECK_CRC_KEY, false);
        editor.commit();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return new IMinistro.Stub()
        {
            public void requestLoader(IMinistroCallback callback, Bundle parameters)
            {
                try
                {
                    if (m_ministroRootPath != null)
                        new Session(MinistroService.this, callback, parameters);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
    }

    public Collection<Integer> getAllSourcesIds()
    {
        return m_sources.values();
    }
}
