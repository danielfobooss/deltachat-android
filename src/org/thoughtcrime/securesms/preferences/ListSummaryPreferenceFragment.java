package org.thoughtcrime.securesms.preferences;


import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEventCenter;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.ApplicationDcContext;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.service.GenericForegroundService;
import org.thoughtcrime.securesms.service.NotificationController;
import org.thoughtcrime.securesms.util.ScreenLockUtil;
import org.thoughtcrime.securesms.util.views.ProgressDialog;

import java.util.Arrays;

public abstract class ListSummaryPreferenceFragment extends CorrectedPreferenceFragment implements DcEventCenter.DcEventDelegate {
  protected static final int REQUEST_CODE_CONFIRM_CREDENTIALS_BACKUP = ScreenLockUtil.REQUEST_CODE_CONFIRM_CREDENTIALS + 1;
  protected static final int REQUEST_CODE_CONFIRM_CREDENTIALS_KEYS = REQUEST_CODE_CONFIRM_CREDENTIALS_BACKUP + 1;
  protected ApplicationDcContext dcContext;
  private NotificationController notificationController;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    dcContext = DcHelper.getContext(getContext());
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_IMEX_PROGRESS, this);
  }

  @Override
  public void onDestroy() {
    dcContext.eventCenter.removeObservers(this);

    if (notificationController != null) {
      // cancel backup when settings-activity is destroyed.
      //
      // where possible, we avoid the settings-activity from being destroyed,
      // however, i did not find a simple way to cancel ConversationListActivity.onNewIntent() -
      // which one is cleaning up "back stack" due to the singleTask flag.
      // using a dummy activity and several workarounds all result even in worse side-effects
      // than cancel-backup when the user relaunches the app.
      // maybe we could bear the singleTask flag or could decouple
      // backup completely from ui-flows -
      // however, all this is some work and probably not maybe the effort just now.
      //
      // anyway, normally, the backup is fast enough and the users will just wait.
      // btw, import does not have this issue (no singleTask in play there)
      // and also for export, switching to other apps and tapping the notification will work.
      // so, the current state is not that bad :)
      notificationController.close();
      dcContext.stopOngoingProcess();
      Toast.makeText(getActivity(), R.string.export_aborted, Toast.LENGTH_LONG).show();
    }

    super.onDestroy();
  }

  protected class ListSummaryListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
      updateListSummary(preference, value);
      return true;
    }
  }

  protected String getSelectedSummary(Preference preference, Object value) {
    ListPreference listPref = (ListPreference) preference;
    int entryIndex = Arrays.asList(listPref.getEntryValues()).indexOf(value);
    return entryIndex >= 0 && entryIndex < listPref.getEntries().length
            ? listPref.getEntries()[entryIndex].toString()
            : getString(R.string.unknown);
  }

  protected void updateListSummary(Preference preference, Object value) {
    ListPreference listPref = (ListPreference) preference;
    listPref.setSummary(getSelectedSummary(preference, value));
  }

  protected void initializeListSummary(ListPreference pref) {
    pref.setSummary(pref.getEntry());
  }

  protected ProgressDialog progressDialog = null;
  protected int            progressWhat = 0;
  protected String         imexDir = "";
  protected void startImex(int what)
  {
    notificationController = GenericForegroundService.startForegroundTask(getContext(), getString(R.string.export_backup_desktop));
    if( progressDialog!=null ) {
      progressDialog.dismiss();
      progressDialog = null;
    }
    progressWhat = what;
    progressDialog = new ProgressDialog(getActivity());
    progressDialog.setMessage(getActivity().getString(R.string.one_moment));
    progressDialog.setCanceledOnTouchOutside(false);
    progressDialog.setCancelable(false);
    progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getActivity().getString(android.R.string.cancel), (dialog, which) -> {
      dcContext.stopOngoingProcess();
      notificationController.close();
      notificationController = null;
    });
    progressDialog.show();

    imexDir = dcContext.getImexDir().getAbsolutePath();
    dcContext.captureNextError();
    dcContext.imex(progressWhat, imexDir);
  }

  @Override
  public void handleEvent(int eventId, Object data1, Object data2) {
    if (eventId== DcContext.DC_EVENT_IMEX_PROGRESS) {
      long progress = (Long)data1;
      if (progress==0/*error/aborted*/) {
        dcContext.endCaptureNextError();
        progressDialog.dismiss();
        progressDialog = null;
        if (dcContext.hasCapturedError()) {
          new AlertDialog.Builder(getActivity())
                  .setMessage(dcContext.getCapturedError())
                  .setPositiveButton(android.R.string.ok, null)
                  .show();
        }
        notificationController.close();
        notificationController = null;
      }
      else if (progress<1000/*progress in permille*/) {
        int percent = (int)progress / 10;
        String formattedPercent = String.format(" %d%%", percent);
        progressDialog.setMessage(getResources().getString(R.string.one_moment) + formattedPercent);
        notificationController.setProgress(1000, progress, formattedPercent);
      }
      else if (progress==1000/*done*/) {
        dcContext.endCaptureNextError();
        progressDialog.dismiss();
        progressDialog = null;
        notificationController.close();
        notificationController = null;
        String msg = "";
        if (progressWhat==DcContext.DC_IMEX_EXPORT_BACKUP) {
          msg = getActivity().getString(R.string.pref_backup_written_to_x, imexDir);
        }
        else if (progressWhat==DcContext.DC_IMEX_EXPORT_SELF_KEYS) {
          msg = getActivity().getString(R.string.pref_managekeys_secret_keys_exported_to_x, imexDir);
        }
        else if (progressWhat==DcContext.DC_IMEX_IMPORT_SELF_KEYS) {
          msg = getActivity().getString(R.string.pref_managekeys_secret_keys_imported_from_x, imexDir);
        }
        new AlertDialog.Builder(getActivity())
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
      }
    }
  }

}
