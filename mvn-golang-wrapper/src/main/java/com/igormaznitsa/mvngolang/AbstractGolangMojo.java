/*
 * Copyright 2016 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.igormaznitsa.mvngolang;

import static com.igormaznitsa.meta.common.utils.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import com.igormaznitsa.meta.annotation.LazyInited;
import com.igormaznitsa.meta.annotation.MustNotContainNull;
import com.igormaznitsa.meta.common.utils.ArrayUtils;
import com.igormaznitsa.meta.common.utils.GetUtils;
import com.igormaznitsa.mvngolang.utils.UnpackUtils;

public abstract class AbstractGolangMojo extends AbstractMojo {

  private static final List<String> ALLOWED_SDKARCHIVE_CONTENT_TYPE = Collections.unmodifiableList(Arrays.asList("application/octet-stream", "application/zip", "application/x-tar"));

  private static final ReentrantLock LOCKER = new ReentrantLock();

  private static final String[] BANNER = new String[]{"______  ___             _________     ______",
    "___   |/  /__   __________  ____/________  / ______ ______________ _",
    "__  /|_/ /__ | / /_  __ \\  / __ _  __ \\_  /  _  __ `/_  __ \\_  __ `/",
    "_  /  / / __ |/ /_  / / / /_/ / / /_/ /  /___/ /_/ /_  / / /  /_/ / ",
    "/_/  /_/  _____/ /_/ /_/\\____/  \\____//_____/\\__,_/ /_/ /_/_\\__, /",
    "                                                           /____/",
    "                  https://github.com/raydac/mvn-golang",
    ""
  };

  /**
   * VERSION, OS, PLATFORM,-OSXVERSION
   */
  public static final String NAME_PATTERN = "go%s.%s-%s%s";

  /**
   * Base site for SDK download.
   */
  @Parameter(name="sdkSite",defaultValue = "https://storage.googleapis.com/golang/")
  private String sdkSite;
  
  /**
   * Hide ASC banner.
   */
  @Parameter(defaultValue = "false", name = "hideBanner")
  private boolean hideBanner;

  /**
   * Folder to be used to save and unpack loaded SDKs and also keep different info.
   */
  @Parameter(defaultValue = "${user.home}${file.separator}.mvnGoLang", name = "storeFolder")
  private String storeFolder;

  /**
   * Folder to be used as GOPATH. NB! The Environment variable also will be checked and if environment variable detected then value of it will be used.
   */
  @Parameter(defaultValue = "${user.home}${file.separator}.mvnGoLang${file.separator}.go_path", name = "goPath")
  private String goPath;

  /**
   * The Go SDK version. It plays role if goRoot is undefined.
   */
  @Parameter(name = "goVersion", defaultValue = "1.6", required = true)
  private String goVersion;

  /**
   * The Go home folder. It can be undefined and in the case the plug-in will make automatic business to find SDK in its cache or download it.
   */
  @Parameter(name = "goRoot")
  private String goRoot;

  /**
   * The Go bootstrap home.
   */
  @Parameter(name = "goRootBootstrap")
  private String goRootBootstrap;

  /**
   * Disable loading GoLang SDK through network if it is not found at cache.
   */
  @Parameter(name = "disableSdkLoad", defaultValue = "false")
  private boolean disableSdkLoad;

  /**
   * GoLang source directory.
   */
  @Parameter(defaultValue = "${basedir}${file.separator}src${file.separator}golang", name = "sources")
  private String sources;

  /**
   * The Target OS.
   */
  @Parameter(name = "targetOs")
  private String targetOs;

  /**
   * The OS. If it is not defined then plug-in will try figure out the current one.
   */
  @Parameter(name = "os")
  private String os;

  /**
   * The Target architecture.
   */
  @Parameter(name = "targetArch")
  private String targetArch;

  /**
   * The Architecture. If it is not defined then plug-in will try figure out the current one.
   */
  @Parameter(name = "arch")
  private String arch;

  /**
   * Version of OSX to be used during distributive name synthesis.
   */
  @Parameter(name = "osxVersion")
  private String osxVersion;

  /**
   * List of optional build flags.
   */
  @Parameter(name = "buildFlags")
  private String[] buildFlags;

  /**
   * Be verbose in logging.
   */
  @Parameter(name = "verbose", defaultValue = "false")
  private boolean verbose;

  /**
   * Do not delete SDK archive after unpacking.
   */
  @Parameter(name = "keepSdkArchive", defaultValue = "false")
  private boolean keepSdkArchive;

  /**
   * Name of tool to be called instead of standard 'go' tool.
   */
  @Parameter(name = "useGoTool")
  private String useGoTool;

  /**
   * Allows to find environment variable values for $GOROOT, $GOROOT_BOOTSTRAP, $GOOS, $GOARCH, $GOPATH and use them for process..
   */
  @Parameter(name = "useEnvVars", defaultValue = "false")
  private boolean useEnvVars;

  /**
   * It allows to define key value pairs which will be used as environment variables for started GoLang process.
   */
  @Parameter(name = "env")
  private Map<?,?> env;

  /**
   * Allows directly define name of SDK archive. If it is not defined then plug-in will try to generate name and find such one in downloaded SDK list..
   */
  @Parameter(name = "sdkArchiveName")
  private String sdkArchiveName;

  /**
   * Directly defined URL to download SDK. In the case SDK list will not be downloaded and plug-in will try download archive through the link.
   */
  @Parameter(name = "sdkDownloadUrl")
  private String sdkDownloadUrl;
  
  /**
   * Keep unpacked wrongly SDK folder.
   */
  @Parameter(name = "keepUnarchFolderIfError", defaultValue = "false")
  private boolean keepUnarchFolderIfError;

  @Nonnull
  public Map<?,?> getEnv() {
    return GetUtils.ensureNonNull(this.env, Collections.EMPTY_MAP);
  }

  @Nullable
  public String getSdkDownloadUrl() {
    return this.sdkDownloadUrl;
  }
  
  public boolean isUseEnvVars() {
    return this.useEnvVars;
  }

  public boolean isKeepSdkArchive() {
    return this.keepSdkArchive;
  }

  public boolean isKeepUnarchFolderIfError() {
    return this.keepUnarchFolderIfError;
  }

  @Nullable
  public String getSdkArchiveName() {
    return this.sdkArchiveName;
  }

  @Nonnull
  public String getStoreFolder() {
    return this.storeFolder;
  }

  @Nullable
  public String getUseGoTool() {
    return this.useGoTool;
  }

  public boolean isVerbose() {
    return this.verbose;
  }

  public boolean isDisableSdkLoad() {
    return this.disableSdkLoad;
  }

  @Nonnull
  public String getSdkSite(){
    return assertNotNull(this.sdkSite);
  }
  
  @Nonnull
  @MustNotContainNull
  public String[] getBuildFlags() {
    return GetUtils.ensureNonNull(this.buildFlags, ArrayUtils.EMPTY_STRING_ARRAY);
  }

  @Nonnull
  public File findGoPath(final boolean ensureExist) throws IOException {
    final String theGoPath = getGoPath();
    final File result = new File(theGoPath);
    if (ensureExist && !result.isDirectory() && !result.mkdirs()) {
      throw new IOException("Can't create folder : " + theGoPath);
    }
    return result;
  }

  @Nullable
  public File findGoRootBootstrap(final boolean ensureExist) throws IOException {
    final String value = getGoRootBootstrap();
    File result = null;
    if (value != null) {
      result = new File(value);
      if (ensureExist && !result.isDirectory()) {
        throw new IOException("Can't find folder for GOROOT_BOOTSTRAP: " + result);
      }
    }
    return result;
  }

  @Nonnull
  public String getOs() {
    String result = this.os;
    if (isSafeEmpty(result)) {
      if (SystemUtils.IS_OS_WINDOWS) {
        result = "windows";
      } else if (SystemUtils.IS_OS_LINUX) {
        result = "linux";
      } else if (SystemUtils.IS_OS_FREE_BSD) {
        result = "freebsd";
      } else {
        result = "darwin";
      }
    }
    return result;
  }

  @Nonnull
  public String getArch() {
    String result = this.arch;
    if (isSafeEmpty(result)) {
      result = investigateArch();
    }
    return result;
  }

  @Nonnull
  public String getGoPath() {
    final String foundInEnvironment = System.getenv("GOPATH");
    String result = assertNotNull(this.goPath);

    if (foundInEnvironment != null && isUseEnvVars()){
      result = foundInEnvironment;
    }
    return result;
  }

  @Nullable
  public String getTargetOS() {
    String result = this.targetOs;
    if (isSafeEmpty(result) && isUseEnvVars()) {
      result = System.getenv("GOOS");
    }
    return result;
  }

  @Nullable
  public String getTargetArch() {
    String result = this.targetArch;
    if (isSafeEmpty(result) && isUseEnvVars()) {
      result = System.getenv("GOARCH");
    }
    return result;
  }

  @Nullable
  public String getOSXVersion() {
    String result = this.osxVersion;
    if (isSafeEmpty(result) && SystemUtils.IS_OS_MAC_OSX) {
      result = "osx10.6";
    }
    return result;
  }

  @Nonnull
  public String getGoVersion() {
    return this.goVersion;
  }

  @Nullable
  public String getGoRoot() {
    String result = this.goRoot;

    if (isSafeEmpty(result) && isUseEnvVars()) {
      result = System.getenv("GOROOT");
    }

    return result;
  }

  @Nullable
  public String getGoRootBootstrap() {
    String result = this.goRootBootstrap;

    if (isSafeEmpty(result) && isUseEnvVars()) {
      result = System.getenv("GOROOT_BOOTSTRAP");
    }

    return result;
  }

  @Nonnull
  public File getSources(final boolean ensureExist) throws IOException {
    final File result = new File(this.sources);
    if (ensureExist && !result.isDirectory()) {
      throw new IOException("Can't find GoLang project sources : " + result);
    }
    return result;
  }

  @LazyInited
  private HttpClient httpClient;

  @Nonnull
  private synchronized HttpClient getHttpClient() {
    if (this.httpClient == null) {
      this.httpClient = new HttpClient();
    }
    return this.httpClient;
  }

  @Nonnull
  private String loadGoLangSdkList() throws IOException {
    final String sdksite = getSdkSite();
    
    getLog().warn("Loading list of available GoLang SDKs from " + sdksite);
    final GetMethod get = new GetMethod(sdksite);

    get.setRequestHeader("Accept", "application/xml");
    try {
      final int status = getHttpClient().executeMethod(get);
      if (status == HttpStatus.SC_OK) {
        final String content = get.getResponseBodyAsString();
        getLog().info("GoLang SDK list has been loaded successfuly");
        getLog().debug(content);
        return content;
      } else {
        throw new IOException("Can't load list of allowed SDKs, status code is " + status);
      }
    } finally {
      get.releaseConnection();
    }
  }

  @Nonnull
  private Document convertSdkListToDocument(@Nonnull final String sdkListAsString) throws IOException {
    try {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      final DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(new InputSource(new StringReader(sdkListAsString)));
    } catch (ParserConfigurationException ex) {
      getLog().error("Can't configure XML parser", ex);
      throw new IOException("Can't configure XML parser", ex);
    } catch (SAXException ex) {
      getLog().error("Can't parse document", ex);
      throw new IOException("Can't parse document", ex);
    } catch (IOException ex) {
      getLog().error("Unexpected IOException", ex);
      throw new IOException("Unexpected IOException", ex);
    }
  }

  private ByteArrayOutputStream consoleErrBuffer;
  private ByteArrayOutputStream consoleOutBuffer;

  private static void deleteFileIfExists(@Nonnull final File file) throws IOException {
    if (file.isFile() && !file.delete()) {
      throw new IOException("Can't delete file : " + file);
    }
  }

  protected void logOptionally(@Nonnull final String message) {
    if (getLog().isDebugEnabled() || this.verbose) {
      getLog().info(message);
    }
  }

  private void initConsoleBuffers() {
    getLog().debug("Initing console out and console err buffers");
    this.consoleErrBuffer = new ByteArrayOutputStream();
    this.consoleOutBuffer = new ByteArrayOutputStream();
  }

  @Nonnull
  private File unpackArchToFolder(@Nonnull final File archiveFile, @Nonnull final String folderInArchive, @Nonnull final File destinationFolder) throws IOException {
    getLog().info(String.format("Unpacking archive %s to folder %s", archiveFile.getName(), destinationFolder.getName()));

    boolean detectedError = true;
    try {

      final int unpackedFileCounter = UnpackUtils.unpackFileToFolder(getLog(), folderInArchive, archiveFile, destinationFolder, true);
      if (unpackedFileCounter == 0) {
        throw new IOException("Couldn't find folder '" + folderInArchive + "' in archive or the archive is empty");
      } else {
        getLog().info("Unpacked " + unpackedFileCounter + " file(s)");
      }

      detectedError = false;

    } finally {
      if (detectedError && !isKeepUnarchFolderIfError()) {
        logOptionally("Deleting folder because error during unpack : " + destinationFolder);
        FileUtils.deleteQuietly(destinationFolder);
      }
    }
    return destinationFolder;
  }

  private static boolean isSafeEmpty(@Nullable final String value) {
    return value == null || value.isEmpty();
  }
  
  @Nonnull
  private static String extractExtensionOfArchive(@Nonnull final String archiveName) {
    final String lcName = archiveName;
    final String result;
    if (lcName.endsWith(".tar.gz")) {
      result = archiveName.substring(archiveName.length()-"tar.gz".length());
    } else {
      result = FilenameUtils.getExtension(archiveName);
    }
    return result;
  }
  
  @Nonnull
  private File loadSDKAndUnpackIntoCache(@Nonnull final File cacheFolder, @Nonnull final String baseSdkName) throws IOException {
    final File sdkFolder = new File(cacheFolder, baseSdkName);

    final String predefinedLink = getSdkDownloadUrl();
    
    final File archiveFile;
    final String linkForDownloading;
    
    if (isSafeEmpty(predefinedLink)) {
      logOptionally("There is not any predefined SDK URL");
      final String sdkFileName = findSdkArchiveFileName(baseSdkName);
      archiveFile = new File(cacheFolder, sdkFileName);
      linkForDownloading = getSdkSite() + sdkFileName;
    } else {
      final String extension = extractExtensionOfArchive(assertNotNull(predefinedLink));
      archiveFile = new File(cacheFolder, baseSdkName+'.'+extension);
      linkForDownloading = predefinedLink;
      logOptionally("Using predefined URL to download SDK : "+linkForDownloading);
      logOptionally("Detected extension of archive : "+extension);
    }

    final GetMethod methodGet = new GetMethod(linkForDownloading);
    methodGet.setFollowRedirects(true);

    boolean errorsDuringLoading = true;

    try {
      if (!archiveFile.isFile()) {
        getLog().warn("Loading SDK archive with URL : " + linkForDownloading);

        final int status = getHttpClient().executeMethod(methodGet);
        if (status != HttpStatus.SC_OK) {
          throw new IOException("Can't load SDK archive for URL : " + linkForDownloading + " [" + status + ']');
        }
        final String contentType = methodGet.getResponseHeader("Content-Type").getValue();

        if (!ALLOWED_SDKARCHIVE_CONTENT_TYPE.contains(contentType)) {
          throw new IOException("Unsupported content type : " + contentType);
        }

        final InputStream inStream = methodGet.getResponseBodyAsStream();
        getLog().info("Downloading SDK archive into file : "+archiveFile);
        FileUtils.copyInputStreamToFile(inStream, archiveFile);

        getLog().info("Archived SDK has been succesfully downloaded, its size is " + (archiveFile.length() / 1024L) + " Kb");

        inStream.close();
      } else {
        getLog().info("Archive file of SDK has been found in the cache : " + archiveFile);
      }

      errorsDuringLoading = false;

      return unpackArchToFolder(archiveFile, "go", sdkFolder);
    } finally {
      methodGet.releaseConnection();
      if (errorsDuringLoading || !this.isKeepSdkArchive()) {
        logOptionally("Deleting archive : " + archiveFile + (errorsDuringLoading ? " (because error during loading)" : ""));
        deleteFileIfExists(archiveFile);
      } else {
        logOptionally("Archive file is kept for special flag : " + archiveFile);
      }
    }
  }

  @Nonnull
  private String extractSDKFileName(@Nonnull final Document doc, @Nonnull final String sdkBaseName, @Nonnull @MustNotContainNull final String[] allowedExtensions) throws IOException {
    getLog().debug("Looking for SDK started with base name : " + sdkBaseName);

    final Set<String> variants = new HashSet<String>();
    for (final String ext : allowedExtensions) {
      variants.add(sdkBaseName + '.' + ext);
    }

    final List<String> listedSdk = new ArrayList<String>();

    final Element root = doc.getDocumentElement();
    if ("ListBucketResult".equals(root.getTagName())) {
      final NodeList list = root.getElementsByTagName("Contents");
      for (int i = 0; i < list.getLength(); i++) {
        final Element element = (Element) list.item(i);
        final NodeList keys = element.getElementsByTagName("Key");
        if (keys.getLength() > 0) {
          final String text = keys.item(0).getTextContent();
          if (variants.contains(text)) {
            logOptionally("Detected compatible SDK in the SDK list : " + text);
            return text;
          } else {
            listedSdk.add(text);
          }
        }
      }

      getLog().error("Can't find any SDK to be used as " + sdkBaseName);
      getLog().error("GoLang list contains listed SDKs");
      getLog().error("..................................................");
      for (final String s : listedSdk) {
        getLog().error(s);
      }

      throw new IOException("Can't find SDK : " + sdkBaseName);
    } else {
      throw new IOException("It is not a ListBucket file [" + root.getTagName() + ']');
    }
  }

  @Nonnull
  private String findSdkArchiveFileName(@Nonnull final String sdkBaseName) throws IOException {
    String result = getSdkArchiveName();
    if (isSafeEmpty(result)) {
      final Document parsed = convertSdkListToDocument(loadGoLangSdkList());
      result = extractSDKFileName(parsed, sdkBaseName, new String[]{"tar.gz", "zip"});
    } else {
      getLog().info("SDK archive name is predefined : "+result);
    }
    return GetUtils.ensureNonNull(result,"");
  }
  
  private void warnIfContainsUC(@Nonnull final String message, @Nonnull final String str) {
    boolean detected = false;
    for(final char c : str.toCharArray()) {
      if (Character.isUpperCase(c)) {
        detected = true;
        break;
      }
    }
    if (detected){
      getLog().warn(message+" : "+str);
    }
  }
  
  @Nonnull
  private File findGoRoot() throws IOException, MojoFailureException {
    final File result;
    LOCKER.lock();
    try {
      final String predefinedGoRoot = this.getGoRoot();

      if (isSafeEmpty(predefinedGoRoot)) {
        final File cacheFolder = new File(this.storeFolder);

        if (!cacheFolder.isDirectory()) {
          logOptionally("Making SDK cache folder : " + cacheFolder);
          if (!cacheFolder.mkdirs()) {
            throw new IOException("Can't create folder " + cacheFolder);
          }
        }

        final String definedOsx = getOSXVersion();

        final String sdkBaseName = String.format(NAME_PATTERN, this.getGoVersion(), this.getOs(), this.getArch(), isSafeEmpty(definedOsx) || !SystemUtils.IS_OS_MAC_OSX ? "" : "-" + definedOsx);
        warnIfContainsUC("Prefer usage of lower case chars only for SDK base name", sdkBaseName);
        
        final File alreadyCached = new File(cacheFolder, sdkBaseName);

        if (alreadyCached.isDirectory()) {
          logOptionally("Cached SDK detected : " + alreadyCached);
          result = alreadyCached;
        } else {
          if (this.disableSdkLoad) {
            throw new MojoFailureException("Can't find " + sdkBaseName + " in the cache but loading is directly disabled");
          }
          result = loadSDKAndUnpackIntoCache(cacheFolder, sdkBaseName);
        }
      } else {
        logOptionally("Detected predefined SDK root folder : "+predefinedGoRoot);
        result = new File(predefinedGoRoot);
        if (!result.isDirectory()) throw new MojoFailureException("Predefined SDK root is not a directory : "+result);
      }
    } finally {
      LOCKER.unlock();
    }
    return result;
  }

  @Nonnull
  private static String investigateArch() {
    final String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
    if (arch.contains("arm")) {
      return "arm";
    }
    if (arch.equals("386") || arch.equals("i386") || arch.equals("x86")) {
      return "386";
    }
    return "amd64";
  }

  private void printBanner() {
    for (final String s : BANNER) {
      getLog().info(s);
    }
  }

  public boolean isHideBanner() {
    return this.hideBanner;
  }

  protected boolean doesNeedOneMoreAttempt(@Nonnull final ProcessResult result, @Nonnull final String consoleOut, @Nonnull final String consoleErr) throws IOException, MojoExecutionException {
    return false;
  }

  @Override
  public final void execute() throws MojoExecutionException, MojoFailureException {

    if (!isHideBanner()) {
      printBanner();
    }

    beforeExecution();

    boolean error = false;
    try {
      int iterations = 0;

      while (true) {
        final ProcessExecutor executor = prepareExecutor();
        final ProcessResult result = executor.executeNoTimeout();
        iterations++;

        final String outLog = extractOutAsString();
        final String errLog = extractErrorOutAsString();

        printLogs(outLog, errLog);

        if (doesNeedOneMoreAttempt(result, outLog, errLog)) {
          if (iterations > 10) {
            throw new MojoExecutionException("Too many iterations detected, may be some loop and bug at mojo " + this.getClass().getName());
          }
          getLog().warn("Make one more attempt...");
        } else {
          assertProcessResult(result);
          break;
        }
      }
    } catch (IOException ex) {
      error = true;
      throw new MojoExecutionException(ex.getMessage(), ex);
    } catch (InterruptedException ex) {
      error = true;
    } finally {
      afterExecution(error);
    }
  }

  public void beforeExecution() {

  }

  public void afterExecution(final boolean error) throws MojoFailureException {

  }

  public boolean enforcePrintOutput() {
    return false;
  }

  @Nonnull
  private String extractOutAsString() {
    return new String(this.consoleOutBuffer.toByteArray(), Charset.defaultCharset());
  }

  @Nonnull
  private String extractErrorOutAsString() {
    return new String(this.consoleErrBuffer.toByteArray(), Charset.defaultCharset());
  }

  protected void printLogs(@Nonnull final String outLog, @Nonnull final String errLog) {
    if ((enforcePrintOutput() || getLog().isDebugEnabled()) && !outLog.isEmpty()) {
      getLog().info("");
      getLog().info("---------Exec.Out---------");
      getLog().info(outLog);
      getLog().info("");
    }

    if (!errLog.isEmpty()) {
      getLog().error("");
      getLog().error("---------Exec.Err---------");
      getLog().error(errLog);
      getLog().error("");
    }

  }

  private void assertProcessResult(@Nonnull final ProcessResult result) throws MojoFailureException {
    final int code = result.getExitValue();
    if (code != 0) {
      throw new MojoFailureException("Process exit code : " + code);
    }
  }

  @Nonnull
  @MustNotContainNull
  public abstract String[] getTailArguments();

  @Nonnull
  @MustNotContainNull
  public String[] getOptionalExtraTailArguments() {
    return ArrayUtils.EMPTY_STRING_ARRAY;
  }

  @Nonnull
  public String getMainGoExecName() {
    return "bin" + File.separatorChar + "go";
  }

  @Nonnull
  public abstract String getGoCommand();

  @Nonnull
  @MustNotContainNull
  public abstract String[] getCommandFlags();

  private void addEnvVar(@Nonnull final ProcessExecutor executor, @Nonnull final String name, @Nonnull final String value) {
    getLog().info(" $" + name + " = " + value);
    executor.environment(name, value);
  }

  @Nonnull
  protected static String adaptExecNameForOS(@Nonnull final String execName) {
    return execName + (SystemUtils.IS_OS_WINDOWS ? ".exe" : "");
  }

  @Nonnull
  private static String getPathToFolder(@Nonnull final File path) {
    String text = path.getAbsolutePath();
    if (!text.endsWith("/") && !text.endsWith("\\")) {
      text = text + File.separatorChar;
    }
    return text;
  }

  @Nonnull
  private ProcessExecutor prepareExecutor() throws IOException, MojoFailureException {
    initConsoleBuffers();
    
    final String execNameAdaptedForOs = adaptExecNameForOS(getMainGoExecName());
    final File detectedRoot = findGoRoot();
    final File executableFile = new File(getPathToFolder(detectedRoot) + FilenameUtils.normalize(GetUtils.ensureNonNull(getUseGoTool(), execNameAdaptedForOs)));

    if (!executableFile.isFile()) {
      throw new MojoFailureException("Can't find executable file : " + executableFile);
    } else {
      logOptionally("Executable file detected : " + executableFile);
    }

    final List<String> commandLine = new ArrayList<String>();
    commandLine.add(executableFile.getAbsolutePath());
    commandLine.add(getGoCommand());

    for (final String s : getCommandFlags()) {
      commandLine.add(s);
    }

    for (final String s : getBuildFlags()) {
      commandLine.add(s);
    }

    for (final String s : getTailArguments()) {
      commandLine.add(s);
    }

    for (final String s : getOptionalExtraTailArguments()) {
      commandLine.add(s);
    }

    final StringBuilder cli = new StringBuilder();
    int index = 0;
    for (final String s : commandLine) {
      if (cli.length() > 0) {
        cli.append(' ');
      }
      if (index == 0) {
        cli.append(execNameAdaptedForOs);
      } else {
        cli.append(s);
      }
      index++;
    }

    logOptionally("Command line : " + cli.toString());

    final ProcessExecutor result = new ProcessExecutor(commandLine);

    final File sourcesFile = getSources(true);
    logOptionally("GoLang project sources folder : " + sourcesFile);
    result.directory(sourcesFile);

    getLog().info("");
    getLog().info("....Environment vars....");

    addEnvVar(result, "GOROOT", detectedRoot.getAbsolutePath());

    final File gopath = findGoPath(true);
    addEnvVar(result, "GOPATH", gopath.getAbsolutePath());

    final String trgtOs = this.getTargetOS();
    final String trgtArch = this.getTargetArch();

    if (trgtOs != null) {
      addEnvVar(result, "GOOS", trgtOs);
    }

    if (trgtArch != null) {
      addEnvVar(result, "GOARCH", trgtArch);
    }

    final File gorootbootstrap = findGoRootBootstrap(true);
    if (gorootbootstrap != null) {
      addEnvVar(result, "GOROOT_BOOTSTRAP", gorootbootstrap.getAbsolutePath());
    }

    for (final Map.Entry<?, ?> record : getEnv().entrySet()) {
      addEnvVar(result, record.getKey().toString(), record.getValue().toString());
    }

    getLog().info("........................");

    result.redirectOutput(this.consoleOutBuffer);
    result.redirectError(this.consoleErrBuffer);

    return result;
  }
}