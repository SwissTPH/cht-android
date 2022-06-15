package org.medicmobile.webapp.mobile;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static org.medicmobile.webapp.mobile.BuildConfig.DEBUG;
import static org.medicmobile.webapp.mobile.MedicLog.error;
import static org.medicmobile.webapp.mobile.MedicLog.log;
import static org.medicmobile.webapp.mobile.MedicLog.trace;
import static org.medicmobile.webapp.mobile.MedicLog.warn;
import static org.medicmobile.webapp.mobile.SimpleJsonClient2.redactUrl;
import static org.medicmobile.webapp.mobile.Utils.createUseragentFrom;
import static org.medicmobile.webapp.mobile.Utils.isValidNavigationUrl;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import android.os.StrictMode;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@SuppressWarnings({ "PMD.GodClass", "PMD.TooManyMethods" })
public class EmbeddedBrowserActivity extends Activity {
	Button download_button;
	Button upload_button;
	public String role = "Placeholder";
	private WebView container;
	private SettingsStore settings;
	private String appUrl;
	private MrdtSupport mrdt;
	private FilePickerHandler filePickerHandler;
	private SmsSender smsSender;
	private ChtExternalAppHandler chtExternalAppHandler;
	private boolean isMigrationRunning = false;

	private static final ValueCallback<String> IGNORE_RESULT = new ValueCallback<String>() {
		public void onReceiveValue(String result) { /* ignore */ }
	};
	private final ValueCallback<String> backButtonHandler = new ValueCallback<String>() {
		public void onReceiveValue(String result) {
			if(!"true".equals(result)) {
				EmbeddedBrowserActivity.this.moveTaskToBack(false);
			}
		}
	};
	private List<String> userData;


	//> ACTIVITY LIFECYCLE METHODS
	@SuppressLint("ClickableViewAccessibility")
	@Override public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		trace(this, "Starting webview...");

		this.filePickerHandler = new FilePickerHandler(this);
		this.mrdt = new MrdtSupport(this);
		this.chtExternalAppHandler = new ChtExternalAppHandler(this);

		try {
			this.smsSender = new SmsSender(this);
		} catch(Exception ex) {
			error(ex, "Failed to create SmsSender.");
		}

		this.settings = SettingsStore.in(this);
		this.appUrl = settings.getAppUrl();
		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.main);
		download_button = findViewById(R.id.download_button);
		download_button.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				String userName = getUserData().get(0);
				Log.d("Cache", userName);

				container.evaluateJavascript("console.log('"+ "username:"+ userName + "')", null);
				String script = "window.PouchDB('medic-user-"+ userName+"')" +
					".allDocs({include_docs: true, attachments: true, binary: true})" +
					".then(result => medicmobile_android.saveDocs(JSON.stringify(result)));";
				container.evaluateJavascript(script, null);
			}
		});
		Boolean isUpdateRole = role != null || role.contains("admin");
		upload_button = findViewById(R.id.upload_button);
		Log.d("Disable with role", role);
		upload_button.setEnabled(isUpdateRole);

		upload_button.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
				i2.setType("*/*");
				startActivityForResult(i2, 105);

			}
		});
		// Add an alarming red border if using configurable (i.e. dev)
		// app with a medic production server.
		if (settings.allowsConfiguration() && appUrl != null && appUrl.contains("app.medicmobile.org")) {
			View webviewContainer = findViewById(R.id.lytWebView);
			webviewContainer.setPadding(10, 10, 10, 10);
			webviewContainer.setBackgroundColor(R.drawable.warning_background);
		}

		// Add a noticeable border to easily identify a training app
		if (BuildConfig.IS_TRAINING_APP) {
			View webviewContainer = findViewById(R.id.lytWebView);
			webviewContainer.setPadding(10, 10, 10, 10);
			webviewContainer.setBackgroundResource(R.drawable.training_background);
		}

		container = findViewById(R.id.wbvMain);
		getFragmentManager()
			.beginTransaction()
			.add(new OpenSettingsDialogFragment(container), OpenSettingsDialogFragment.class.getName())
			.commit();

		configureUserAgent();

		setUpUiClient(container);
		enableRemoteChromeDebugging();
		enableJavascript(container);
		enableStorage(container);

		enableUrlHandlers(container);

		Intent appLinkIntent = getIntent();
		Uri appLinkData = appLinkIntent.getData();
		browseTo(appLinkData);

		if (settings.allowsConfiguration()) {
			toast(redactUrl(appUrl));
		}

		registerRetryConnectionBroadcastReceiver();

		String recentNavigation = settings.getLastUrl();
		if (isValidNavigationUrl(appUrl, recentNavigation)) {
			container.loadUrl(recentNavigation);
		}
	}

	@SuppressWarnings("PMD.CallSuperFirst")
	@Override
	protected void onStart() {
		trace(this, "onStart() :: Checking Crosswalk migration ...");
		XWalkMigration xWalkMigration = new XWalkMigration(this.getApplicationContext());
		if (xWalkMigration.hasToMigrate()) {

			log(this, "onStart() :: Running Crosswalk migration ...");
			isMigrationRunning = true;
			Intent intent = new Intent(this, UpgradingActivity.class)
				.putExtra("isClosable", false)
				.putExtra("backPressedMessage", getString(R.string.waitMigration));
			startActivity(intent);
			xWalkMigration.run();
			role = getUserData().get(0);
			Log.d("Role is ", role);

		} else {
			trace(this, "onStart() :: Crosswalk installation not found - skipping migration");
		}
		trace(this, "onStart() :: Checking Crosswalk migration done.");

		if (BuildConfig.IS_TRAINING_APP) {
			toast(getString(R.string.usingTrainingApp));
		}

		super.onStart();
	}

	@Override
	protected void onStop() {
		String recentNavigation = container.getUrl();
		if (isValidNavigationUrl(appUrl, recentNavigation)) {
			try {
				settings.setLastUrl(recentNavigation);
			} catch (SettingsException e) {
				error(e, "Error recording last URL loaded");
			}
		}
		super.onStop();
	}

	@Override public void onBackPressed() {
		trace(this, "onBackPressed()");
		container.evaluateJavascript(
				"angular.element(document.body).injector().get('AndroidApi').v1.back()",
				backButtonHandler);
	}

	@SuppressLint("Recycle")
	@Override
	protected void onActivityResult(int requestCd, int resultCode, Intent intent) {
		Optional<RequestCode> requestCodeOpt = RequestCode.valueOf(requestCd);

		if (!requestCodeOpt.isPresent()) {
			trace(this, "onActivityResult() :: no handling for requestCode=%s", requestCd);
			return;
		}

		RequestCode requestCode = requestCodeOpt.get();

		try {
			trace(this, "onActivityResult() :: requestCode=%s, resultCode=%s", requestCode.name(), resultCode);

			switch (requestCode) {
				case PICK_FILE_REQUEST:
					if (resultCode == RESULT_OK && intent != null) {
						//URI data_uri = new URI(intent.getData().toString());
						Uri data_uri = intent.getData();
						//File file = new File(String.valueOf(data_uri));
						String content;
						//Scanner myReader = new Scanner(file);
						//while (myReader.hasNextLine()) {
							//content.append(myReader.nextLine());

						//}
						//myReader.close();
						try {
							InputStream in = getContentResolver().openInputStream(data_uri);
							BufferedReader r = new BufferedReader(new InputStreamReader(in));
							StringBuilder total = new StringBuilder();
							for (String line; (line = r.readLine()) != null; ) {
								total.append(line);
								//evaluateJavascript("console.log('reading line"+line+"')");
							}
							if (Build.VERSION.SDK_INT > 9) {
								StrictMode.ThreadPolicy policy =
									new StrictMode.ThreadPolicy.Builder().permitAll().build();
								StrictMode.setThreadPolicy(policy);
							}

							//content =total.toString().replaceAll("\"total_rows\".*\"rows\":","\"docs\":");
							content =total.toString().replaceAll("\"total_rows\".*\"rows\":","\"docs\":");

							//content = "{\"docs\":[{\"id\":\"185d1ba0-da75-4adf-b43f-c2148b50981c\",\"key\":\"180d1ba0-da75-4adf-b43f-c2148b50981c\",\"value\":{\"rev\":\"2-95f842364467f65af90e735a5a21c6d1\"},\"doc\":{\"parent\":{\"_id\":\"e43d3be2-49df-5462-ae9f-bc013a6cfce1\",\"parent\":{\"_id\":\"98d63f7d-768e-581d-9cae-fcf3cb7cda22\",\"parent\":{\"_id\":\"1f5f0769-ab9f-5b3f-b63b-25ea123346ac\"}}},\"type\":\"person\",\"name\":\"offline_person_39\",\"short_name\":\"\",\"date_of_birth\":\"2020-06-11\",\"date_of_birth_method\":\"\",\"ephemeral_dob\":{\"dob_calendar\":\"2020-06-11\",\"dob_method\":\"\",\"ephemeral_months\":\"6\",\"ephemeral_years\":\"2022\",\"dob_approx\":\"2022-06-11\",\"dob_raw\":\"2020-06-11\",\"dob_iso\":\"2020-06-11\"},\"sex\":\"male\",\"phone\":\"\",\"phone_alternate\":\"\",\"role\":\"patient\",\"external_id\":\"\",\"notes\":\"\",\"meta\":{\"created_by\":\"hp1_dagahaley_cons\",\"created_by_person_uuid\":\"576896af-02bc-5a37-9d14-1b674fdb0b2e\",\"created_by_place_uuid\":\"e43d3be2-49df-5462-ae9f-bc013a6cfce1\"},\"reported_date\":1654973074761,\"patient_id\":\"64350\",\"_id\":\"180d1ba0-da75-4adf-b43f-c2148b50981c\",\"_rev\":\"2-95f842364467f65af90e735a5a21c6d1\"}}]}";
							//content = content.replaceAll(",\"_rev\":.*?\\}","}");
							//content = content.replaceAll("\"id\"", "\"_id\"");
							//content = content.replaceAll("\"value\":.*?,(\"doc\")", "$1");
							//content = content.replaceAll("\"doc\":\\{(.*?)\"\\}","$1\"");

							//content = content.replaceAll("\"id\":.*\\},$", "");
							//content = content.replaceAll("\"deleted\"""\"_deleted\"");
							//content = content.replaceAll("\"attachments\"", "\"_attachments\"");
							//content = content.replaceAll("\"doc\":\\{(\".*)\\}", "$1");

							//content = content.substring(0, content.length() - 1);
							//content = content + ",\"new_edits\": false}";
							Log.d("Content of the file ", content);
							Log.d("Tail content file", content.substring(content.length() - 150));
// Post downloaded data to the REST API / Main server
							//maybe use all_docs but iterate through the docs OR use jAVASCRIPT with the db
							Log.d("APP uRL is ", appUrl);
							URL url = new URL(appUrl+"/medic/_bulk_docs?include_docs=true");
							Log.d("URL using", url.toString());
							String userPassword = "medic" + ":" + "password";
							String encoding = Base64.encodeToString(userPassword.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
							HttpURLConnection con = (HttpURLConnection) url.openConnection();
							con.setRequestMethod("POST");
							con.setRequestProperty("Authorization", "Basic " + encoding);
							con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
							con.setRequestProperty("Accept", "application/json");
							con.setDoOutput(true);
							con.setDoInput(true);
							con.connect();
							byte[] input = content.getBytes(StandardCharsets.UTF_8);
							try(OutputStream os = con.getOutputStream()) {
								Log.d("input is currently ", content);
								os.write(input, 0, input.length);
								//os.flush();
								Log.d("input done ", "yes");
							}catch (Exception e){
								e.printStackTrace();
							}
							//String content_js =
							/*String script = "var localDB=new PouchDB('temp_db');" +
								"localDB.bulkDocs("+content+", [include_docs=true, attachments=true,new_edits=false]).then(function(err, response) {" +
								"if (err) {" +
								"console.log(JSON.stringify(err));} else {" +
								"console.log('Post request: '+JSON.stringify(response));" +
								"var remoteDB = new PouchDB('"+appUrl+"/medic"+"');"+
								"localDB.replicate.to(remoteDB, {include_docs: true,new_edits:false}).on('complete', function (result){" +
								"console.log(JSON.stringify(result));"+
								"medicmobile_android.toastResult('Replication completed');}).on('error', function(err){" +
								"medicmobile_android.toastResult('Error with replication');"+
								"console.log(JSON.stringify(err));});}}).catch(e => {" +
								"console.log(JSON.stringify(e));" +
								"});" +
								"var localDB2 = window.PouchDB('medic-user-medic');"+
								"localDB.replicate.to(localDB2,{include_docs: true,new_edits:false}).on('complete', function (result){" +
								"console.log(JSON.stringify(result));"+
								"medicmobile_android.toastResult('Replication completed');}).on('error', function(err){" +
								"medicmobile_android.toastResult('Error with replication');"+
								"console.log(JSON.stringify(err));});";*/
							//Log.d("script to exe", script);
							//container.evaluateJavascript(script, null);
							Log.d("resp", con.getResponseMessage()+" ; "+con.getResponseCode());
							String responseMessage = con.getResponseMessage();
							Integer responseCode = con.getResponseCode();
							if (con.getResponseCode() == 200 || con.getResponseCode() == 201) {
								try (BufferedReader br = new BufferedReader(
									new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
									StringBuilder response = new StringBuilder();
									String responseLine = null;
									while ((responseLine = br.readLine()) != null) {
										response.append(responseLine.trim());
									}
									con.disconnect();
									Log.d("Response", response.toString());
									JSONArray array = new JSONArray(response.toString());
									Toast.makeText(getApplicationContext(), responseMessage, Toast.LENGTH_LONG).show();
								} catch (Exception e) {
									Log.d("Input message loop", "There was no Input stream");
									e.printStackTrace();
								}
							}else {
								try (BufferedReader br = new BufferedReader(
									new InputStreamReader(con.getErrorStream(), StandardCharsets.UTF_8))) {
									StringBuilder response = new StringBuilder();
									String responseLine = null;
									while ((responseLine = br.readLine()) != null) {
										response.append(responseLine.trim());
									}
									JSONObject response_obj = new JSONObject(response.toString());
									Log.d("response of the call", response.toString() + ": "+ response_obj.getString("reason"));
									Toast.makeText(getApplicationContext(), response_obj.getString("error") + "with reason : "+ response_obj.getString("reason"), Toast.LENGTH_LONG).show();
								} catch (Exception e) {
									Log.d("Error loop ", "There was an Error");
									e.printStackTrace();
								}
							}
							con.disconnect();
						}catch (Exception e) {
							warn(e, "Could not open the specified file");
							toast("Could not open the specified file");
						}
						//container.evaluateJavascript("console.log('+"+data_uri+"')", null);
						//container.evaluateJavascript("console.log('"+ "username:"+ userName + "')", null);
						//String import_script =
						//	""+
						//"window.PouchDB('medic-user-"+ userName+"')" +
						//	".put('"+content +"')"+
						//	".then(result => toastResult(result));"+
						//	"')";
						//container.evaluateJavascript(import_script, null);
					}

				case FILE_PICKER_ACTIVITY:
					this.filePickerHandler.processResult(resultCode, intent);
					return;
				case GRAB_MRDT_PHOTO_ACTIVITY:
					processMrdtResult(requestCode, intent);
					return;
				case CHT_EXTERNAL_APP_ACTIVITY:
					processChtExternalAppResult(resultCode, intent);
					return;
				case ACCESS_STORAGE_PERMISSION:
					processStoragePermissionResult(resultCode, intent);
					return;
				case ACCESS_LOCATION_PERMISSION:
					processLocationPermissionResult(resultCode);
					return;
				default:
					trace(this, "onActivityResult() :: no handling for requestCode=%s", requestCode.name());
			}
		} catch (Exception ex) {
			String action = intent == null ? null : intent.getAction();
			warn(ex, "Problem handling intent %s (%s) with requestCode=%s & resultCode=%s",
				intent, action, requestCode.name(), resultCode);
		}
	}

//> ACCESSORS
	MrdtSupport getMrdtSupport() {
		return this.mrdt;
	}

	SmsSender getSmsSender() {
		return this.smsSender;
	}

	ChtExternalAppHandler getChtExternalAppHandler() {
		return this.chtExternalAppHandler;
	}
//> PUBLIC API
	public void evaluateJavascript(final String js) {
		evaluateJavascript(js, true);
	}

	public void evaluateJavascript(final String js, final boolean useLoadUrl) {
		int maxUrlSize = 2097100; // Maximum character limit supported for loading as url.

		if (useLoadUrl && js.length() <= maxUrlSize) {
			// `WebView.loadUrl()` seems to be significantly faster than `WebView.evaluateJavascript()` on Tecno Y4.
			container.post(() -> container.loadUrl("javascript:" + js, null));
		} else {
			container.post(() -> container.evaluateJavascript(js, IGNORE_RESULT));
		}
	}

	public void errorToJsConsole(String message, Object... extras) {
		String formatted = String.format(message, extras);
		String escaped = formatted.replace("'", "\\'");
		evaluateJavascript("console.error('" + escaped + "');");
	}

	public boolean isMigrationRunning() {
		return isMigrationRunning;
	}

	public void setMigrationRunning(boolean migrationRunning) {
		isMigrationRunning = migrationRunning;
	}

	public boolean getLocationPermissions() {
		boolean hasFineLocation = ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED;
		boolean hasCoarseLocation = ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED;

		if (hasFineLocation && hasCoarseLocation) {
			trace(this, "getLocationPermissions() :: already granted");
			return true;
		}

		if (settings.hasUserDeniedGeolocation()) {
			trace(this, "getLocationPermissions() :: user has previously denied to share location");
			locationRequestResolved();
			return false;
		}

		trace(this, "getLocationPermissions() :: location not granted before, requesting access...");
		startActivityForResult(
			new Intent(this, RequestLocationPermissionActivity.class),
			RequestCode.ACCESS_LOCATION_PERMISSION.getCode()
		);
		return false;
	}

//> PRIVATE HELPERS
	private void locationRequestResolved() {
		evaluateJavascript("window.CHTCore.AndroidApi.v1.locationPermissionRequestResolved();");
	}

	private List<String> getUserData(){
		List<String> userData = new ArrayList<>();
		try {
			String cookies = CookieManager.getInstance().getCookie(appUrl);
			if ( cookies != null & !cookies.isEmpty()){
				String encodedUserCtxCookie = Arrays.stream(cookies.split(";"))
					.map(field -> field.split("="))
					.filter(pair -> "userCtx".equals(pair[0].trim()))
					.map(pair -> pair[1].trim())
					.findAny()
					.get();
				String userCtxData = URLDecoder.decode(encodedUserCtxCookie, "utf-8")
					.replace("{", "")
					.replace("}", "");
				String userName = Arrays.stream(userCtxData.split(","))
					.map(field -> field.split(":"))
					.filter(pair -> "\"name\"".equals(pair[0].trim()))
					.map(pair -> pair[1].replace("\"", "").trim())
					.findAny()
					.get();
				userData.add(userName);
				role = (Arrays.stream(userCtxData.split(","))
					.map(field -> field.split(":"))
					.filter(pair -> "\"roles\"".equals(pair[0].trim()))
					.map(pair -> pair[1].replace("\"", "").trim())
					.findAny()
					.get());
				userData.add(role);
				return userData;
			}
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}

	private void processLocationPermissionResult(int resultCode) {
		if (resultCode != RESULT_OK) {
			try {
				settings.setUserDeniedGeolocation();
			} catch (SettingsException e) {
				error(e, "processLocationPermissionResult :: Error recording negative to access location.");
			}
		}

		locationRequestResolved();
	}

	private void processChtExternalAppResult(int resultCode, Intent intentData) {
		String script = this.chtExternalAppHandler.processResult(resultCode, intentData);
		trace(this, "ChtExternalAppHandler :: Executing JavaScript: %s", script);
		evaluateJavascript(script);
	}

	private void processMrdtResult(RequestCode requestCode, Intent intent) {
		String js = mrdt.process(requestCode, intent);
		trace(this, "Executing JavaScript: %s", js);
		evaluateJavascript(js);
	}

	private void processStoragePermissionResult(int resultCode, Intent intent) {
		String triggerClass = intent == null ? null : intent.getStringExtra(RequestStoragePermissionActivity.TRIGGER_CLASS);

		if (FilePickerHandler.class.getName().equals(triggerClass)) {
			trace(this, "EmbeddedBrowserActivity :: Resuming FilePickerHandler process. Trigger:%s", triggerClass);
			this.filePickerHandler.resumeProcess(resultCode);
			return;
		}

		if (ChtExternalAppHandler.class.getName().equals(triggerClass)) {
			trace(this, "EmbeddedBrowserActivity :: Resuming ChtExternalAppHandler activity. Trigger:%s", triggerClass);
			this.chtExternalAppHandler.resumeActivity(resultCode);
			return;
		}

		trace(
			this,
			"EmbeddedBrowserActivity :: No handling for trigger: %s, requestCode: %s",
			triggerClass,
			RequestCode.ACCESS_STORAGE_PERMISSION.name()
		);
	}

	private void configureUserAgent() {
		String current = WebSettings.getDefaultUserAgent(this);
		container.getSettings().setUserAgentString(createUseragentFrom(current));
	}

	private void browseTo(Uri url) {
		String urlToLoad = this.settings.getUrlToLoad(url);
		trace(this, "Pointing browser to: %s", redactUrl(urlToLoad));
		container.loadUrl(urlToLoad, null);
	}

	private void enableRemoteChromeDebugging() {
		WebView.setWebContentsDebuggingEnabled(true);
	}

	private void setUpUiClient(WebView container) {
		container.setWebChromeClient(new WebChromeClient() {
			@Override public boolean onConsoleMessage(ConsoleMessage cm) {
				if (!DEBUG) {
					return super.onConsoleMessage(cm);
				}
				trace(this, "onConsoleMessage() :: %s:%s | %s", cm.sourceId(), cm.lineNumber(), cm.message());
				return true;
			}

			@Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
				filePickerHandler.openPicker(fileChooserParams, filePathCallback);
				return true;
			}

			@Override public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback callback) {
				callback.invoke(origin, true, true);
			}
		});
	}

	@SuppressLint("SetJavaScriptEnabled")
	private void enableJavascript(WebView container) {
		container.getSettings().setJavaScriptEnabled(true);

		MedicAndroidJavascript maj = new MedicAndroidJavascript(this);
		maj.setAlert(new Alert(this));

		maj.setActivityManager((ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE));

		maj.setConnectivityManager((ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE));

		container.addJavascriptInterface(maj, "medicmobile_android");
	}

	private void enableStorage(WebView container) {
		WebSettings settings = container.getSettings();
		settings.setDomStorageEnabled(true);
		settings.setDatabaseEnabled(true);
	}

	private void enableUrlHandlers(WebView container) {
		container.setWebViewClient(new UrlHandler(this, settings));
	}

	private void toast(String message) {
		if (message != null) {
			Toast.makeText(container.getContext(), message, Toast.LENGTH_LONG).show();
		}
	}

	private void registerRetryConnectionBroadcastReceiver() {
		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				if (action.equals("retryConnection")) {
					// user fixed the connection and asked the app
					// to retry the load from the connection error activity
					evaluateJavascript("window.location.reload()", false);
				}
			}
		};
		registerReceiver(broadcastReceiver, new IntentFilter("retryConnection"));
	}

//> ENUMS
	public enum RequestCode {
		ACCESS_LOCATION_PERMISSION(100),
		ACCESS_STORAGE_PERMISSION(101),
		CHT_EXTERNAL_APP_ACTIVITY(102),
		GRAB_MRDT_PHOTO_ACTIVITY(103),
		FILE_PICKER_ACTIVITY(104),
		PICK_FILE_REQUEST(105);
		private final int requestCode;

		RequestCode(int requestCode) {
			this.requestCode = requestCode;
		}

		public static Optional<RequestCode> valueOf(int code) {
			return Arrays
				.stream(RequestCode.values())
				.filter(e -> e.getCode() == code)
				.findFirst();
		}

		public int getCode() {
			return requestCode;
		}
	}

}
