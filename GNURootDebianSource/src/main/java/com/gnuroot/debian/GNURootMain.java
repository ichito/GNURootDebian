/*
Copyright (c) 2014 Teradyne

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */

/* Author(s): Corbin Leigh Champion */

package com.gnuroot.debian;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import com.iiordanov.bVNC.Constants;

public class GNURootMain extends Activity {

	//Launch types. GNURoot Debian expects this to be stored in the "launchType" extra in LAUNCH intents.
	public static final String GNUROOT_TERM = "launchTerm";
	public static final String GNUROOT_XTERM = "launchXTerm";

	Boolean errOcc;
	Boolean expectingResult = false;
	ProgressDialog pdRing;
	Integer downloadResultCode;
	File mainFile;
    Intent savedIntent = null;
    boolean noInstallAgain = false;

	/**
	 * GNURoot Debian behaves based on the intent it receives (if it exists), or installs the rootfs if it does not
	 * yet exist, or launches into a prooted terminal.
	 * Possible intent actions:
	 * 1. LAUNCH. See handleLaunchIntent.
	 * 2. GNUROOT_REINSTALL. Reinstalls the rootfs, not the app.
	 * 3. UPDATE_ERROR. An internal intent sent from GNURoot service to handle intents sent from the old ecosystem.
	 * @param savedInstanceState
     */
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

        handleIntent(getIntent());
	}

    private void handleIntent(Intent intent) {
        String intentAction = intent.getAction();

        File installStatus = new File(getInstallDir().getAbsolutePath() +"/support/.gnuroot_rootfs_passed");
		if ((!isSymlink(installStatus) && !installStatus.exists()) && (noInstallAgain == false)) {
            GNURootMain.this.runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(getApplicationContext(), R.string.toast_not_installed, Toast.LENGTH_LONG).show();
                }
            });
            savedIntent = intent;
            setupSupportFiles(true);
            updateVersion();
            setupFirstHalf();
        } else if (intentAction.equals("com.gnuroot.debian.GNUROOT_REINSTALL")) {
            setupSupportFiles(true);
            updateVersion();
            setupFirstHalf();
        } else if (intentAction.equals("com.gnuroot.debian.LAUNCH")) {
            noInstallAgain = false;
            handleLaunchIntent(intent);
        }  else if (intentAction.equals("com.gnuroot.debian.UPDATE_ERROR"))
            showUpdateErrorButton(intent.getStringExtra("packageName"));
        /*
        else if(intentAction == "com.gnuroot.debian.TOAST_ALARM")
            makeAlarmToast(intent);
        */
        else
            launchTerm(null);
    }

	/**
	 * Launches a prooted term or xterm depending on the intent. Will also untar a shared file if it exists.
	 * @param intent
     */
	public void handleLaunchIntent(Intent intent) {
		Uri sharedFile = intent.getData();
		String launchType = intent.getStringExtra("launchType");
		String command = intent.getStringExtra("command");

        if (command != null) {
            File file = new File(getInstallDir().getAbsolutePath() + "/support/newCommand");
            try {
                OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(file));
                outputStreamWriter.write(command);
                outputStreamWriter.close();
            } catch (IOException e) {
                Log.e("Exception", "File write failed: " + e.toString());
            }
            exec("chmod 0777 " + file.getAbsolutePath(), true);
            command = "/support/newCommand";
        }

        if(sharedFile != null) {
			copySharedFile(sharedFile);
		}

		switch (launchType) {
			case GNUROOT_TERM:
				launchTerm(command);
				return;
			case GNUROOT_XTERM:
				launchXTerm(!intent.getBooleanExtra("terminal_button", false), command);
				return;
			default:
				Toast.makeText(this, R.string.toast_bad_launch_type, Toast.LENGTH_LONG).show();
				finish();
		}
	}

	/**
	 * Sends an intent to Android Terminal Emulator to launch PRoot, which installs any missing components.
	 * @param command is the command that will be executed when PRoot launches.
	 */
	public void launchTerm(String command) {
		Intent termIntent = new Intent(this, jackpal.androidterm.RunScript.class);
		termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
		//if (true)
		//	termIntent.putExtra("jackpal.androidterm.iInitialCommand",
		//			getInstallDir().getAbsolutePath() + "/support/busybox sh");
		//else
		if(command == null)
			termIntent.putExtra("jackpal.androidterm.iInitialCommand",
					getInstallDir().getAbsolutePath() + "/support/launchProot /bin/bash");
		else
			termIntent.putExtra("jackpal.androidterm.iInitialCommand",
					getInstallDir().getAbsolutePath() + "/support/launchProot " + command);

        checkPatches();

		startActivity(termIntent);
		finish();
	}

	/**
	 * Launches a VNC session by sending an intent to create a new xterm to Android Terminal Emulator, waiting for
	 * status files to be created, and then sending an intent to bVNC.
	 * @param createNewXTerm determines if a new xterm should be launched regardless of whether one is already active.
	 */
	public void launchXTerm(Boolean createNewXTerm, String command) {
		File deleteStarted = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_started");
		if (deleteStarted.exists())
			deleteStarted.delete();

		Intent termIntent = new Intent(this, jackpal.androidterm.RunScript.class);
		termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
		if(command == null)
			command = "/bin/bash";
		if (createNewXTerm)
			termIntent.putExtra("jackpal.androidterm.iInitialCommand",
					getInstallDir().getAbsolutePath() + "/support/launchXterm " + command);
		else
			// Button presses will not open a new xterm is one is alrady running.
			termIntent.putExtra("jackpal.androidterm.iInitialCommand",
					getInstallDir().getAbsolutePath() + "/support/launchXterm  button_pressed " + command);


        checkPatches();

		startActivity(termIntent);

		final ScheduledExecutorService scheduler =
				Executors.newSingleThreadScheduledExecutor();

		scheduler.scheduleAtFixedRate
				(new Runnable() {
					public void run() {
						File checkStarted = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_started");
						File checkRunning = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_running");
						if (checkStarted.exists() || checkRunning.exists()) {
							Intent bvncIntent = new Intent(getBaseContext(), com.iiordanov.bVNC.RemoteCanvasActivity.class);
							bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?" + Constants.PARAM_VNC_PWD + "=gnuroot"));
							bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
							startActivity(bvncIntent);
							scheduler.shutdown();
						}
					}
				}, 3, 2, TimeUnit.SECONDS); //Avoid race case in which tightvnc needs to be restarted

		finish();
	}

	/**
	 * Copy a file from another app. This can be a tar file or a singular script.
	 * @param sharedFile must be a tar.gz file. The intent must include flag FLAG_GRANT_READ_URI_PERMISSION
     */
	private void copySharedFile(Uri sharedFile) {
		InputStream srcStream;
		String untarCommand;
		boolean status = true;
		File srcFile = new File(sharedFile.getPath());
		File destFolder = getInstallDir();
		File destFile = new File(destFolder.getAbsolutePath() + "/support/" + srcFile.getName());

		try {
			srcStream = getContentResolver().openInputStream(sharedFile);
			try { copyFile(srcStream, new FileOutputStream(destFile)); }
			catch (IOException e) { status = false; }
		} catch (FileNotFoundException e) {
			status = false;
		}

		if (!status) {
			Toast.makeText(this, R.string.toast_file_not_found, Toast.LENGTH_LONG).show();
		}

		return;

	}

	/**
	 * Displays an alert dialog if another app isn't updated to at least the version that included the
	 * new ecosystem changes. Sends the user to the market page for it if not.
	 */
	private void showUpdateErrorButton(final String packageName) {
		AlertDialog.Builder builder = new AlertDialog.Builder(GNURootMain.this);
		builder.setMessage(R.string.update_error_message);
		builder.setPositiveButton(R.string.button_affirmative, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
				if(packageName != null){
					Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setData(Uri.parse("market://details?id=" + packageName));
					startActivity(intent);
				}
				finish();
			}
		});
		builder.create().show();
	}

	/*
	TODO This was going to be used to check status files.
	private void makeAlarmToast(Intent intent) {
	  	String alarmName = intent.getStringExtra("alarmName");
	 	Toast.makeText(this, alarmName + "... Please wait for completion.", Toast.LENGTH_LONG).show();
	}
	 */

	/**
	 * Creates /support and copies from project assets directory to proper rootfs locations.
	 * @param deleteFirst indicates to blow /support away first.
     */
	public void setupSupportFiles(boolean deleteFirst) {
		File installDir = getInstallDir();

		if (deleteFirst)
			deleteRecursive(new File(installDir.getAbsolutePath() + "/support"));

		//create directory for support executables and scripts
		File tempFile = new File(installDir.getAbsolutePath() + "/support");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//copy bare necessities to the now created support directory
		copyAssets("com.gnuroot.debian");

		/* TODO This isn't used currently, but will need to be handled somehow eventually.
		String shadowOption = " ";

		//create a script for running a command in proot
		tempFile = new File(installDir.getAbsolutePath() + "/support/launchProot");
		if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.KITKAT) {
			shadowOption = ((CheckBox) findViewById(R.id.sdcard_checkbox)).isChecked() ? " -n " : " ";
		}
		*/
	}

	/**
	 * Launches the GNURootDownloaderActivity.
	 */
	private void setupFirstHalf() {
		errOcc = false;

		expectingResult = true;
		Intent downloadIntent = new Intent();
		downloadIntent.addCategory(Intent.CATEGORY_DEFAULT);
		downloadIntent.setClassName("com.gnuroot.debian", "com.gnuroot.debian.GNURootDownloaderActivity");
		startActivityForResult(downloadIntent, 0);
	}


	/**
	 * Cancels the downloader notification caused by GNURootDownloaderActivity.
	 * @param ctx
     */
	public static void cancelNotifications(Context ctx) {
		NotificationManager notifManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
		notifManager.cancelAll();
	}

	/**
	 * If GNURootDownloaderActivity is successful, kills the downloader notification and launches setupSecondHalf.
	 * @param requestCode
	 * @param resultCode
	 * @param data
     */
	@Override
	protected void onActivityResult(int requestCode, int resultCode,
									Intent data) {
		if (expectingResult) {
			cancelNotifications(this);
			expectingResult = false;
			if (requestCode == 0) {
				if (resultCode > 0) {
					downloadResultCode = resultCode;
					Thread t = new Thread() {
						public void run() {
							try {
								setupSecondHalf(downloadResultCode);
							} catch (Exception e) {
								pdRing.dismiss();
								GNURootMain.this.runOnUiThread(new Runnable() {
									public void run() {
										Toast.makeText(getApplicationContext(), R.string.toast_bad_install, Toast.LENGTH_LONG).show();
									}
								});
							}
						}
					};
					t.start();
				} else {
					pdRing.dismiss();
					GNURootMain.this.runOnUiThread(new Runnable() {
						public void run() {
							GNURootMain.this.runOnUiThread(new Runnable() {
								public void run() {
									Toast.makeText(getApplicationContext(), R.string.toast_bad_install, Toast.LENGTH_LONG).show();
								}
							});
						}
					});
				}
			}
		}
	}

	/**
	 * Saves the location of the architecture specific obb in @mainFile, then creates
	 * basic directories for the file system and finally launches a term.
	 * @param obbVersion
     */
	private void setupSecondHalf(int obbVersion) {

		String path = Environment.getExternalStorageDirectory() + "/Android/obb/com.gnuroot.debian/";
		mainFile = new File(path + "main." + Integer.toString(obbVersion) + ".com.gnuroot.debian.obb");
		File installDir = getInstallDir();
		File sdcardInstallDir = getSdcardInstallDir();

		//setup some basic directories

		//create internal install directory
		File tempFile = new File(installDir.getAbsolutePath() + "/debian");
		//blow away if it already existed
		if (tempFile.exists()) {
			deleteRecursive(tempFile);
		}
		tempFile.mkdir();

		//create external noexec parent directory
		tempFile = new File(sdcardInstallDir.getAbsolutePath());
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create external noexec directory
		tempFile = new File(sdcardInstallDir.getAbsolutePath() + "/debian");
		//blow it aways if it already existed
		if (tempFile.exists()) {
			deleteRecursive(tempFile);
		}
		tempFile.mkdir();

		//create a directory for binding the android rootfs to
		tempFile = new File(installDir.getAbsolutePath() + "/debian/host-rootfs");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the noexec directory to
		tempFile = new File(installDir.getAbsolutePath() + "/debian/.proot.noexec");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the sdcard to
		tempFile = new File(installDir.getAbsolutePath() + "/debian/sdcard");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath() + "/debian/dev");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath() + "/debian/proc");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath() + "/debian/mnt");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath() + "/debian/data");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}


		tempFile = new File(installDir.getAbsolutePath() + "/debian/sys");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the home to
		tempFile = new File(installDir.getAbsolutePath() + "/debian/home");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a home directory on sdcard
		tempFile = new File(sdcardInstallDir.getAbsolutePath() + "/home");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for firing intents from
		tempFile = new File(installDir.getAbsolutePath() + "/debian/intents");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create an intents directory on the sdcard
		tempFile = new File(sdcardInstallDir.getAbsolutePath() + "/intents");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

        if ((savedIntent == null) || (savedIntent.getAction() != "com.gnuroot.debian.LAUNCH"))
		    launchTerm(null);
        else {
            noInstallAgain = true;
            handleIntent(savedIntent);
        }

	}

	public File getInstallDir() {
		try {
			return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
		} catch (NameNotFoundException e) {
			return null;
		}
	}

	public File getSdcardInstallDir() {
		return new File(Environment.getExternalStorageDirectory() + "/GNURoot");
	}

	/**
	 * Recursively deletes everything at @fileOrDirectory and below.
	 * @param fileOrDirectory
     */
	void deleteRecursive(File fileOrDirectory) {
		exec(getInstallDir().getAbsolutePath() + "/support/busybox rm -rf " + fileOrDirectory.getAbsolutePath(), true);
	}

	/**
	 * Copies a file from @in to @out.
	 * @param in
	 * @param out
	 * @throws IOException
     */
	private void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
	}

	public void copyDirectory(File sourceLocation , File targetLocation)
			throws IOException {

		if (sourceLocation.isDirectory()) {
			if (!targetLocation.exists()) {
				targetLocation.mkdir();
			}

			String[] children = sourceLocation.list();
			for (int i=0; i<children.length; i++) {
				copyDirectory(new File(sourceLocation, children[i]),
						new File(targetLocation, children[i]));
			}
		} else {

			InputStream in = new FileInputStream(sourceLocation);
			OutputStream out = new FileOutputStream(targetLocation);

			// Copy the bits from instream to outstream
			byte[] buf = new byte[1024];
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
			in.close();
			out.close();
		}
	}

	/**
	 * Copies assets from the armhf|armhf|i386 /assets directory to /data/user/0/support.
	 * This is bound to ROOTDIR/support when execInProot is ran.
	 * @param packageName
     */
	private void copyAssets(String packageName) {
		Context friendContext = null;
		try {
			friendContext = this.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY);
		} catch (NameNotFoundException e1) {
			return;
		}
		AssetManager assetManager = friendContext.getAssets();
		File tempFile = new File(getInstallDir().getAbsolutePath() + "/support");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}
		String[] files = null;
		try {
			files = assetManager.list("");
		} catch (IOException e) {
			Log.e("tag", "Failed to get asset file list.", e);
		}
		for (String filename : files) {
			InputStream in = null;
			OutputStream out = null;
			boolean replaceStrings = false;
			try {
				in = assetManager.open(filename);
				if (filename.contains(".replace.mp2")) {
					replaceStrings = true;
				}
				filename = filename.replace(".replace.mp2", "");
				filename = filename.replace(".mp2", "");
				filename = filename.replace(".mp3", ".tar.gz");
				File outFile = new File(tempFile, filename);
				out = new FileOutputStream(outFile);
				copyFile(in, out);
				in.close();
				in = null;
				out.flush();
				out.close();
				out = null;
				if (replaceStrings) {
					replaceScriptStrings(outFile);
				}
				exec("chmod 0777 " + outFile.getAbsolutePath(), true);
			} catch (IOException e) {
				Log.e("tag", "Failed to copy asset file: " + filename, e);
			}
		}
	}

	/**
	 * Replaces key phrases in scripts copied with the asset manager.
	 * @param myFile
     */
	private void replaceScriptStrings(File myFile) {
		File installDir = getInstallDir();
		File sdcardInstallDir = getSdcardInstallDir();
		String sdcardPath = Environment.getExternalStorageDirectory().getAbsolutePath();
		String mainFilePath = Environment.getExternalStorageDirectory() + "/Android/obb/com.gnuroot.debian/main." + BuildConfig.OBBVERSION + ".com.gnuroot.debian.obb";

		String oldFileName = myFile.getAbsolutePath();
		String tmpFileName = oldFileName + ".tmp";

		BufferedReader br = null;
		BufferedWriter bw = null;
		try {
			br = new BufferedReader(new FileReader(oldFileName));
			bw = new BufferedWriter(new FileWriter(tmpFileName));
			String line;
			while ((line = br.readLine()) != null) {
				line = line.replace("ROOT_PATH", installDir.getAbsolutePath());
				line = line.replace("SDCARD_GNU_PATH", sdcardInstallDir.getAbsolutePath());
				line = line.replace("SDCARD_PATH", sdcardPath);
				line = line.replace("EXTRA_BINDINGS", "");
				line = line.replace("MAIN_FILE_PATH", mainFilePath);
				bw.write(line + "\n");
			}
		} catch (Exception e) {
			Toast.makeText(this, R.string.toast_bad_install, Toast.LENGTH_LONG).show();
			return;
		} finally {
			try {
				if (br != null)
					br.close();
			} catch (IOException e) {
				//
			}
			try {
				if (bw != null)
					bw.close();
			} catch (IOException e) {
				//
			}
		}
		// Once everything is complete, delete old file..
		File oldFile = new File(oldFileName);
		oldFile.delete();

		// And rename tmp file's name to old file name
		File newFile = new File(tmpFileName);
		newFile.renameTo(oldFile);

	}

	/**
	 * Executes @command and sets @setError if an error occurs.
	 * @param command
	 * @param setError
     */
	private void exec(String command, boolean setError) {
		Runtime runtime = Runtime.getRuntime();
		Process process;
		try {
			process = runtime.exec(command);
			try {
				String str;
				process.waitFor();
				BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
				while ((str = stdError.readLine()) != null) {
					Log.e("Exec", str);
					if (setError)
						errOcc = true;
				}
				process.getInputStream().close();
				process.getOutputStream().close();
				process.getErrorStream().close();
			} catch (InterruptedException e) {
				if (setError)
					errOcc = true;
			}
		} catch (IOException e1) {
			errOcc = true;
		}
	}

    /**
     * Checks whether the package.versionName is the same as the the versionName stored in shared preferences. If it
	 * is not, it deletes the patch passed status file so that the launchProot script will apply the new patches.
     */
    private void checkPatches() {
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        PackageInfo pi;
        String patchVersion = "notARealPatchVersion";
        String sharedVersion = prefs.getString("patchVersion", null);
        try {
            pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            patchVersion = pi.versionName;
        } catch (NameNotFoundException e) {
            Toast.makeText(getApplicationContext(), R.string.toast_bad_package, Toast.LENGTH_LONG).show();
        }

        if (sharedVersion != null && !sharedVersion.equals(patchVersion)) {
            File patchStatus = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_patch_passed");
            deleteRecursive(patchStatus);
            Toast.makeText(this, R.string.toast_bad_patch, Toast.LENGTH_LONG).show();
        }

		if ((sharedVersion == null) || (!sharedVersion.equals(patchVersion))) {
			    setupSupportFiles(false);
			try {
				copyDirectory(new File(getInstallDir().getAbsolutePath() + "/debian/home"), new File(getSdcardInstallDir().getAbsolutePath() + "/home"));
			} catch (IOException e) {
				//e.printStackTrace();
			}
		}

		if (!patchVersion.equals("notARealPatchVersion")) {
			editor.putString("patchVersion", patchVersion);
			editor.commit();
		}
    }

    private void updateVersion() {
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        PackageInfo pi;
        String patchVersion = "notARealPatchVersion";
        try {
            pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            patchVersion = pi.versionName;
        } catch (NameNotFoundException e) {
            Toast.makeText(getApplicationContext(), R.string.toast_bad_package, Toast.LENGTH_LONG).show();
        }

        if (!patchVersion.equals("notARealPatchVersion")) {
            editor.putString("patchVersion", patchVersion);
            editor.commit();
        }
    }

	public static boolean isSymlink(File file) {
		try {
			if (file == null)
				throw new NullPointerException("File must not be null");
			File canon;
			if (file.getParent() == null) {
				canon = file;
			} else {
				File canonDir;

				canonDir = file.getParentFile().getCanonicalFile();

				canon = new File(canonDir, file.getName());
			}
			return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
		} catch (IOException e) {
			return false;
		}
	}
}