/*
    Copyright (c) 2011-2014, BogDan Vatra <bogdan@kde.org>

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

import java.io.File;
import java.io.FileInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;

public class Session
{
    // / Ministro server parameter keys
    private static final String REQUIRED_MODULES_KEY = "required.modules";
    private static final String APPLICATION_TITLE_KEY = "application.title";
    private static final String SOURCES_KEY = "sources";
    private static final String REPOSITORY_KEY = "repository";
    private static final String MINIMUM_MINISTRO_API_KEY = "minimum.ministro.api";
    private static final String MINIMUM_QT_VERSION_KEY = "minimum.qt.version";
    public static final String UPDATE_KEY = "update";
    private static final String ANDROID_THEMES_KEY = "android.themes";
    // / Ministro server parameter keys

    // / loader parameter keys
    private static final String ERROR_CODE_KEY = "error.code";
    private static final String ERROR_MESSAGE_KEY = "error.message";
    private static final String DEX_PATH_KEY = "dex.path";
    private static final String LIB_PATH_KEY = "lib.path";
    private static final String LIBS_PATH_KEY = "libs.path";
    private static final String LOADER_CLASS_NAME_KEY = "loader.class.name";
    private static final String STATIC_INIT_CLASSES_KEY = "static.init.classes";

    private static final String NATIVE_LIBRARIES_KEY = "native.libraries";
    private static final String ENVIRONMENT_VARIABLES_KEY = "environment.variables";
    private static final String APPLICATION_PARAMETERS_KEY = "application.parameters";
    private static final String QT_VERSION_PARAMETER_KEY = "qt.version.parameter";
    // / loader parameter keys

    // / loader error codes
    private static final int EC_NO_ERROR = 0;
    private static final int EC_INCOMPATIBLE = 1;
    private static final int EC_NOT_FOUND = 2;
    private static final int EC_INVALID_PARAMETERS = 3;
    private static final int EC_INVALID_QT_VERSION = 4;
    private static final int EC_DOWNLOAD_CANCELED = 5;
    // / loader error codes

    // used to check Ministro Service compatibility
    private static final int MINISTRO_MIN_API_LEVEL = 1;
    private static final int MINISTRO_MAX_API_LEVEL = 4;

    public static final String[] NECESSITAS_SOURCE = { "https://files.kde.org/necessitas/ministro/android/necessitas/" };
    public static final String MINISTRO_VERSION = "MINISTRO_VERSION";

    private MinistroService m_service = null;
    private LibrariesStruct m_libraries = null;
    private String m_pathSeparator = null;
    private IMinistroCallback m_callback = null;
    private Bundle m_parameters = null;
    private String m_repository = null;

    private ArrayList<Integer> m_sourcesIds = null;
    private SparseArray<HashMap<String, Library>> m_downloadedLibrariesMap = new SparseArray<HashMap<String, Library>>();
    private int m_displayDPI = -1;
    private String[] m_themes = null;

    private boolean m_onlyExtractStyleAndSsl = false;
    private boolean m_extractStyle = false;

    public boolean onlyExtractStyleAndSsl()
    {
        return m_onlyExtractStyleAndSsl;
    }

    public boolean extractStyle()
    {
        return m_extractStyle;
    }

    public String getMinistroSslRootPath()
    {
        return  m_service.getMinistroSslRootPath();
    }

    public SharedPreferences getPreferences()
    {
        return m_service.getPreferences();
    }

    public int getDisplayDPI()
    {
        return m_displayDPI;
    }

    public String[] getThemes()
    {
        return m_themes;
    }

    public Session(MinistroService service, IMinistroCallback callback, Bundle parameters)
    {
        m_service = service;
        m_callback = callback;
        m_parameters = parameters;
        m_displayDPI = m_service.getResources().getDisplayMetrics().densityDpi;
        if (!parameters.getBoolean(UPDATE_KEY, false))
            m_sourcesIds = m_service.getSourcesIds(getSources());
        else
        {
            m_sourcesIds = new ArrayList<Integer>();
            m_sourcesIds.addAll(m_service.getAllSourcesIds());
        }
        m_pathSeparator = System.getProperty("path.separator", ":");
        long startTime = System.currentTimeMillis();
        refreshLibraries(m_service.checkCrc());
        long endTime = System.currentTimeMillis();
        Log.i(MinistroService.TAG, "refreshLibraries took " + (endTime - startTime) + " ms");
        if (!parameters.getBoolean(UPDATE_KEY, false))
        {
            startTime = System.currentTimeMillis();
            checkModulesImpl(true, null);
            endTime = System.currentTimeMillis();
            Log.i(MinistroService.TAG, "checkModulesImpl took " + (endTime - startTime) + " ms");
        }
    }

    private void setDeviceThemes(String[] themes)
    {
        m_extractStyle = !new File(m_service.getMinistroStyleRootPath(m_displayDPI) + "style.json").exists();
        ArrayList<String> deviceThemes = new ArrayList<String>();
        if (themes != null)
            for(String theme: themes)
            {
                try {
                    android.R.style.class.getDeclaredField(theme);
                    deviceThemes.add(theme);
                    m_extractStyle |= !new File(m_service.getMinistroStyleRootPath(m_displayDPI) + theme).exists();
                } catch (NoSuchFieldException e) {
                    e.printStackTrace();
                }
            }

        if (deviceThemes.size() == 0)
            return;

        m_themes = new String[deviceThemes.size()];
        m_themes = deviceThemes.toArray(m_themes);
    }

    final void checkModulesImpl(boolean downloadMissingLibs, Result res)
    {
        if (!m_parameters.containsKey(REQUIRED_MODULES_KEY) || !m_parameters.containsKey(APPLICATION_TITLE_KEY) || !m_parameters.containsKey(MINIMUM_MINISTRO_API_KEY)
                || !m_parameters.containsKey(MINIMUM_QT_VERSION_KEY))
        {
            Bundle loaderParams = new Bundle();
            loaderParams.putInt(ERROR_CODE_KEY, EC_INVALID_PARAMETERS);
            loaderParams.putString(ERROR_MESSAGE_KEY, m_service.getResources().getString(R.string.invalid_parameters));
            try
            {
                m_callback.loaderReady(loaderParams);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            Log.e(MinistroService.TAG, "Invalid parameters: " + m_parameters.toString());
            return;
        }

        if (m_parameters.containsKey(ANDROID_THEMES_KEY))
            setDeviceThemes(m_parameters.getStringArray(ANDROID_THEMES_KEY));
        else
            setDeviceThemes(null);

        SharedPreferences preferences = m_service.getPreferences();
        try
        {
            m_extractStyle |= !preferences.getString(MINISTRO_VERSION, "").equals(m_service.getPackageManager().getPackageInfo(m_service.getPackageName(), 0).versionName);
        }
        catch (PackageManager.NameNotFoundException e)
        {
            e.printStackTrace();
        }

        m_onlyExtractStyleAndSsl = false;

        int qtApiLevel = m_parameters.getInt(MINIMUM_QT_VERSION_KEY);
        if (qtApiLevel > m_libraries.qtVersion) // the application needs a newer qt
                                    // version
        {
            if (m_parameters.getBoolean(QT_VERSION_PARAMETER_KEY, false))
            {
                Bundle loaderParams = new Bundle();
                loaderParams.putInt(ERROR_CODE_KEY, EC_INVALID_QT_VERSION);
                loaderParams.putString(ERROR_MESSAGE_KEY, m_service.getResources().getString(R.string.invalid_qt_version));
                try
                {
                    m_callback.loaderReady(loaderParams);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                Log.e(MinistroService.TAG, "Invalid qt verson");
                return;
            }
            m_parameters.putBoolean(QT_VERSION_PARAMETER_KEY, true);
            m_service.startRetrieval(this);
            return;
        }

        int ministroApiLevel = m_parameters.getInt(MINIMUM_MINISTRO_API_KEY);
        if (ministroApiLevel < MINISTRO_MIN_API_LEVEL || ministroApiLevel > MINISTRO_MAX_API_LEVEL)
        {
            // panic !!! Ministro service is not compatible, user should upgrade
            // Ministro package
            Bundle loaderParams = new Bundle();
            loaderParams.putInt(ERROR_CODE_KEY, EC_INCOMPATIBLE);
            loaderParams.putString(ERROR_MESSAGE_KEY, m_service.getResources().getString(R.string.incompatible_ministo_api));
            try
            {
                m_callback.loaderReady(loaderParams);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            Log.e(MinistroService.TAG, "Ministro cannot satisfy API version: " + ministroApiLevel);
            return;
        }

        // check necessitasApiLevel !!! I'm pretty sure some people will
        // completely ignore my warning
        // and they will deploying apps to Android Market, so let's try to give
        // them a chance.

        // this method is called by the activity client who needs modules.
        Bundle loaderParams = checkModules(null);

        if (downloadMissingLibs && loaderParams.getInt(ERROR_CODE_KEY) == EC_NO_ERROR)
            m_onlyExtractStyleAndSsl = m_extractStyle | !new File(m_service.getMinistroSslRootPath()).exists();
        else
            m_onlyExtractStyleAndSsl = false;

        if (m_onlyExtractStyleAndSsl || (downloadMissingLibs && loaderParams.getInt(ERROR_CODE_KEY) == EC_NOT_FOUND) )
        {
            m_service.startRetrieval(this);
        }
        else
        {
            try
            {
                if (!downloadMissingLibs && res == Result.Canceled)
                {
                    loaderParams.putInt(ERROR_CODE_KEY, EC_DOWNLOAD_CANCELED);
                    loaderParams.putString(ERROR_MESSAGE_KEY, m_service.getResources().getString(R.string.ministro_canceled));
                }
                Library.mergeBundleParameters(loaderParams, ENVIRONMENT_VARIABLES_KEY, m_parameters, ENVIRONMENT_VARIABLES_KEY);
                Library.mergeBundleParameters(loaderParams, APPLICATION_PARAMETERS_KEY, m_parameters, APPLICATION_PARAMETERS_KEY);
                m_callback.loaderReady(loaderParams);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private String[] getSources()
    {
        if (!m_parameters.containsKey(SOURCES_KEY))
            return NECESSITAS_SOURCE;
        return m_parameters.getStringArray(SOURCES_KEY);
    }

    public ArrayList<Integer> getSourcesIds()
    {
        return m_sourcesIds;
    }

    String getRepository()
    {
        if (m_repository == null)
        {
            if (!m_parameters.containsKey(REPOSITORY_KEY))
                m_repository = m_service.getRepository();
            else
            {
                m_repository = m_parameters.getString(REPOSITORY_KEY);
                if (!m_repository.equals("stable") && !m_repository.equals("testing") && !m_repository.equals("unstable"))
                    m_repository = m_service.getRepository();
            }
        }
        return m_repository;
    }

    String getApplicationName()
    {
        return m_parameters.getString(APPLICATION_TITLE_KEY);
    }

    URL getVersionsFileUrl(Integer sourceId) throws MalformedURLException
    {
        return new URL(m_service.getSource(sourceId) + getRepository() + "/" + android.os.Build.CPU_ABI + "/android-" + android.os.Build.VERSION.SDK_INT + "/versions.xml");
    }

    URL getLibsXmlUrl(Integer sourceId, String version) throws MalformedURLException
    {
        return new URL(m_service.getSource(sourceId) + getRepository() + "/" + android.os.Build.CPU_ABI + "/android-" + android.os.Build.VERSION.SDK_INT + "/libs-" + version + ".xml");
    }

    // this method reload all downloaded libraries
    void refreshLibraries(boolean checkCrc)
    {
        m_libraries = m_service.refreshLibraries(m_sourcesIds, m_displayDPI, checkCrc);
    }

    private SparseArray<Double> m_versions = new SparseArray<Double>();

    public double getVersion(Integer sourceId)
    {
        if (m_versions.indexOfKey(sourceId) >= 0)
            return m_versions.get(sourceId);
        return -1;
    }

    public enum Result
    {
        Completed, Canceled
    }

    /**
    * Helper method for the last step of the retrieval process.
    *
    * <p>
    * Checks the availability of the requested modules and informs the
    * requesting application about it via the {@link IMinistroCallback}
    * instance.
    * </p>
    *
    */
    void retrievalFinished(Result res)
    {
        synchronized (SourcesCache.sync)
        {
            for(int sourceId : m_sourcesIds)
                SourcesCache.s_sourcesCache.remove(sourceId);
        }
        refreshLibraries(false);
        checkModulesImpl(false, res);
    }

    /**
    * Checks whether a given list of libraries are readily accessible (e.g.
    * usable by a program).
    *
    * <p>
    * If the <code>notFoundModules</code> argument is given, the method fills
    * the list with libraries that need to be retrieved first.
    * </p>
    *
    * @param notFoundModules
    * @return true if all modules are available
    */
    Bundle checkModules(HashMap<String, Library> notFoundModules)
    {
        Bundle params = new Bundle();
        boolean res = true;
        ArrayList<Module> libs = new ArrayList<Module>();
        Set<String> jars = new HashSet<String>();
        ArrayList<String> initClasses = new ArrayList<String>();
        for (String module : m_parameters.getStringArray(REQUIRED_MODULES_KEY))
        {
            // don't stop on first error
            boolean r = addModules(module, libs, notFoundModules, jars, initClasses);
            if ( !r )
            {
              Log.d( "Ministro", "Missing " + module );
            }
            res &= r;
        }

        ArrayList<String> librariesArray = new ArrayList<String>();
        // sort all libraries
        Collections.sort(libs, new ModuleCompare());
        for (Module lib : libs)
            librariesArray.add(lib.path);
        params.putStringArrayList(NATIVE_LIBRARIES_KEY, librariesArray);

        ArrayList<String> jarsArray = new ArrayList<String>();
        for (String jar : jars)
            jarsArray.add(jar);

        params.putString(DEX_PATH_KEY, Library.join(jarsArray, m_pathSeparator));
        params.putString(LOADER_CLASS_NAME_KEY, m_libraries.loaderClassName);
        if (initClasses.size() > 0)
            params.putStringArray(STATIC_INIT_CLASSES_KEY, initClasses.toArray(new String[initClasses.size()]));

        try
        {
            params.putString(LIB_PATH_KEY, m_service.getLibsRootPath(m_sourcesIds.get(0), getRepository()));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        ArrayList<String> paths = new ArrayList<String>();
        for (Integer id : m_sourcesIds)
            paths.add(m_service.getLibsRootPath(id, getRepository()));
        params.putStringArrayList(LIBS_PATH_KEY, paths);
        params.putString(ENVIRONMENT_VARIABLES_KEY, joinEnvironmentVariables());
        params.putString(APPLICATION_PARAMETERS_KEY, Library.join(m_libraries.applicationParams, "\t"));
        params.putInt(ERROR_CODE_KEY, res ? EC_NO_ERROR : EC_NOT_FOUND);
        if (!res)
            params.putString(ERROR_MESSAGE_KEY, m_service.getResources().getString(R.string.dependencies_error));
        return params;
    }

    /**
    * Helper method for the module resolution mechanism. It deals with an
    * individual module's resolution request.
    *
    * <p>
    * The method checks whether a given <em>single</em> <code>module</code> is
    * already accessible or needs to be retrieved first. In the latter case the
    * method returns <code>false</code>.
    * </p>
    *
    * <p>
    * The method traverses a <code>module<code>'s dependencies automatically.
    * </p>
    *
    * <p>
    * In order to find out whether a <code>module</code> is accessible the
    * method consults the list of downloaded libraries. If found, an entry to
    * the <code>modules</code> list is added.
    * </p>
    *
    * <p>
    * In case the <code>module</code> is not immediately accessible and the
    * <code>notFoundModules</code> argument exists, a list of available
    * libraries is consulted to fill a list of modules which yet need to be
    * retrieved.
    * </p>
    *
    * @param module
    * @param modules
    * @param notFoundModules
    * @param jars
    * @return <code>true</code> if the given module and all its dependencies
    *         are readily available.
    */
    private boolean addModules(String module, ArrayList<Module> modules, HashMap<String, Library> notFoundModules, Set<String> jars, ArrayList<String> initClasses)
    {
        // Module argument is not supposed to be null at this point.
        if (modules == null)
            return false; // we are in deep shit if this happens

        // Short-cut: If the module is already in our list of previously found
        // modules then we do not
        // need to consult the list of downloaded modules.
        for (int i = 0; i < modules.size(); i++)
        {
            if (modules.get(i).name.equals(module))
                return true;
        }

        // Consult the list of downloaded modules. If a matching entry is found,
        // it is added to the
        // list of readily accessible modules and its dependencies are checked
        // via a recursive call.
        Library library = m_libraries.downloadedLibraries.get(module);
        if (library != null)
        {
            Module m = new Module();
            m.name = library.name;
            m.path = m_service.getLibsRootPath(library.sourceId, getRepository()) + library.filePath;
            m.level = library.level;
            if (library.needs != null)
                for (NeedsStruct needed : library.needs)
                {
                    if (needed.type != null && needed.type.equals("jar"))
                        jars.add(m_service.getLibsRootPath(library.sourceId, getRepository()) + needed.filePath);
                    if (needed.initClass != null)
                        initClasses.add(needed.initClass);
                }
            modules.add(m);

            boolean res = true;
            if (library.depends != null)
                for (String depend : library.depends)
                    res &= addModules(depend, modules, notFoundModules, jars, initClasses);

            if (library.replaces != null)
                for (String replaceLibrary : library.replaces)
                    for (int mIt = 0; mIt < modules.size(); mIt++)
                        if (replaceLibrary.equals(modules.get(mIt).name))
                            modules.remove(mIt--);

            return res;
        }

        // Requested module is not readily accessible.
        if (notFoundModules != null)
        {
            // Checks list of modules which are known to not be readily
            // accessible and returns early to
            // prevent double entries.
            if (notFoundModules.get(module) != null)
                return false;

            // Deal with not yet readily accessible module's dependencies.
            library = m_libraries.availableLibraries.get(module);
            if (library != null)
            {
                Log.i(MinistroService.TAG, "Module '"+ module + "' not found");
                notFoundModules.put(module, library);
                if (library.depends != null)
                    for (int depIt = 0; depIt < library.depends.length; depIt++)
                        addModules(library.depends[depIt], modules, notFoundModules, jars, initClasses);
            }
        }
        return false;
    }

    /**
    * Sorter for libraries.
    *
    * Hence the order in which the libraries have to be loaded is important, it
    * is necessary to sort them.
    */
    static private class ModuleCompare implements Comparator<Module>
    {
        public int compare(Module a, Module b)
        {
            return a.level - b.level;
        }
    }

    /**
    * Helper class which allows manipulating libraries.
    *
    * It is similar to the {@link Library} class but has fewer fields.
    */
    static private class Module
    {
        String path;
        String name;
        int level;
    }

    private static void cleanLibrary(String rootPath, Library lib)
    {
        try
        {
            new File(rootPath + lib.filePath).delete();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (lib.needs != null)
            for (NeedsStruct n : lib.needs)
            {
                try
                {
                    new File(rootPath + n.filePath).delete();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
    }

    synchronized public HashMap<String, Library> getChangedLibraries(Integer sourceId)
    {
        try
        {
            HashMap<String, Library> oldLibs = m_downloadedLibrariesMap.get(sourceId);
            File file = new File(m_service.getVersionXmlFile(sourceId, getRepository()));
            if (!file.exists() || oldLibs == null)
                return null;

            DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentFactory.newDocumentBuilder();
            Document dom = documentBuilder.parse(new FileInputStream(file));
            Element root = dom.getDocumentElement();
            root.normalize();
            Node node = root.getFirstChild();

            HashMap<String, Library> newLibraries = new HashMap<String, Library>();
            Library.loadLibs(node, m_service.getLibsRootPath(sourceId, getRepository()), sourceId, newLibraries, null, false);
            HashMap<String, Library> changedLibs = new HashMap<String, Library>();
            String rootPath = m_service.getLibsRootPath(sourceId, getRepository());

            for (String library : oldLibs.keySet())
            {
                Library newLib = newLibraries.get(library);
                Library oldLib = oldLibs.get(library);
                // Check the sha1 of this library and of the needed files
                // to see if we really need to download something
                 if (newLib == null)
                 {
                     // the new libraries list doesn't contain this library
                     // anymore, so we must remove it with all its needed files
                     cleanLibrary(rootPath, oldLib);
                     continue;
                 }
                 // we must check the sha1 check sum of the both files.
                 boolean changed = false;
                 if (!newLib.sha1.equals(oldLib.sha1))
                     changed = true;
                 else
                 {
                     // the lib doesn't have any needed files
                     if (newLib.needs == null && oldLib.needs == null)
                         continue;

                     if ((newLib.needs == null && oldLib.needs != null) || (newLib.needs != null && oldLib.needs == null) || newLib.needs.length != oldLib.needs.length)
                         changed = true;
                     else
                     {
                         for (NeedsStruct newNeedsStruct : newLib.needs)
                         {
                            boolean found = false;
                            for (NeedsStruct oldNeedsStruct : oldLib.needs)
                            {
                                if (newNeedsStruct.name.equals(oldNeedsStruct.name) && newNeedsStruct.sha1.equals(oldNeedsStruct.sha1))
                                {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found)
                            {
                                changed = true;
                                break;
                            }
                         }
                     }
                 }

                 if (changed)
                 {
                     cleanLibrary(rootPath, oldLibs.get(library));
                     changedLibs.put(library + "_" + sourceId, newLib);
                 }
            }
            return changedLibs;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    private String joinEnvironmentVariables()
    {
        String env = new String();
        for (String key : m_libraries.environmentVariables.keySet())
        {
            if (env.length() > 0)
                env += "\t";
            env += key + "=" + m_libraries.environmentVariables.get(key);
        }
        return env;
    }
}
