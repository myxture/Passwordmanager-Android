package hu.cshb.passwordmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.util.Pair;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import hu.cshb.passwordmanager.databinding.ActivityMainBinding;
import hu.cshb.passwordmanager.databinding.LayoutGenerateBinding;
import hu.cshb.passwordmanager.databinding.LayoutNewBinding;
import hu.cshb.passwordmanager.databinding.LayoutReadBinding;

@FunctionalInterface
interface MarginSetter {
    void setMargin(NumberPicker numberPicker, int marginLeft);
}

public class MainActivity extends AppCompatActivity {
    private static final char CHAR_LOWEST = 33;
    private static final char CHAR_HIGHEST = 126;

    private static final String NO_PASSWORDS = "<no passwords>";

    private String mFileToExport;
    private String mLastLogMessage;

    private ActivityMainBinding mActivityMainBinding;
    private LayoutNewBinding mLayoutNewBinding;
    private LayoutReadBinding mLayoutReadBinding;
    private LayoutGenerateBinding mLayoutGenerateBinding;

    private List<String> mPasswordNames;
    private ArrayAdapter<String> mAdapter;

    // Settings
    private boolean mDarkTheme;
    private boolean mConfirmPasswords;
    private boolean mShowPasswords;

    private LinearLayout.LayoutParams mDefaultSpaceLayoutParams;

    @StyleRes int mAppTheme;

    ActivityResultLauncher<String[]> mActivityResultImport;
    ActivityResultLauncher<Uri> mActivityResultExport;
    ActivityResultLauncher<Uri> mActivityResultExportSingle;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("editText_new_filename", mLayoutNewBinding.editTextNewFilename.getText().toString());
        outState.putString("editText_new_masterPassword", mLayoutNewBinding.editTextNewMasterPassword.getText().toString());
        outState.putString("editText_new_masterPasswordConfirm", mLayoutNewBinding.editTextNewMasterPasswordConfirm.getText().toString());
        outState.putString("editText_new_passwordToStore", mLayoutNewBinding.editTextNewPasswordToStore.getText().toString());
        outState.putString("editText_new_passwordToStoreConfirm", mLayoutNewBinding.editTextNewPasswordToStoreConfirm.getText().toString());
        outState.putString("editText_read_search", mLayoutReadBinding.editTextReadSearch.getText().toString());
        outState.putString("editText_read_masterPassword", mLayoutReadBinding.editTextReadMasterPassword.getText().toString());
        outState.putInt("numberPicker_generate_length", mLayoutGenerateBinding.numberPickerGenerateLength.getValue());
        outState.putInt("numberPicker_generate_lettersMin", mLayoutGenerateBinding.numberPickerGenerateLettersMin.getValue());
        outState.putInt("numberPicker_generate_lettersMax", mLayoutGenerateBinding.numberPickerGenerateLettersMax.getValue());
        outState.putInt("numberPicker_generate_numbersMin", mLayoutGenerateBinding.numberPickerGenerateNumbersMin.getValue());
        outState.putInt("numberPicker_generate_numbersMax", mLayoutGenerateBinding.numberPickerGenerateNumbersMax.getValue());
        outState.putInt("numberPicker_generate_symbolsMin", mLayoutGenerateBinding.numberPickerGenerateSymbolsMin.getValue());
        outState.putInt("numberPicker_generate_symbolsMax", mLayoutGenerateBinding.numberPickerGenerateSymbolsMax.getValue());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        mDarkTheme = preferences.getBoolean("dark_theme", false);
        mConfirmPasswords = preferences.getBoolean("confirm_passwords", true);
        mShowPasswords = preferences.getBoolean("show_passwords", false);

        mAppTheme = mDarkTheme ? R.style.AppThemeDark : R.style.AppThemeLight;
        setTheme(mAppTheme);

        final LayoutInflater layoutInflater = getLayoutInflater();
        mActivityMainBinding = ActivityMainBinding.inflate(layoutInflater);
        mLayoutNewBinding = LayoutNewBinding.inflate(layoutInflater);
        mLayoutReadBinding = LayoutReadBinding.inflate(layoutInflater);
        mLayoutGenerateBinding = LayoutGenerateBinding.inflate(layoutInflater);

        mPasswordNames = new ArrayList<>();
        final File[] passwordFiles = getFilesDir().listFiles(pathname -> pathname.getName().endsWith(".pwd"));
        if (passwordFiles == null || passwordFiles.length == 0)
            mPasswordNames.add(NO_PASSWORDS);
        else for (File passwordFile : passwordFiles)
            mPasswordNames.add(passwordFile.getName().replace(".pwd", ""));
        Collections.sort(mPasswordNames, String.CASE_INSENSITIVE_ORDER);

        mActivityMainBinding.viewPager2.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                final int lengthLeft = mLayoutGenerateBinding.numberPickerGenerateLength.getLeft();
                final int letterLeft = mLayoutGenerateBinding.numberPickerGenerateLettersMin.getLeft();
                final int numberLeft = mLayoutGenerateBinding.numberPickerGenerateNumbersMin.getLeft();
                final int symbolLeft = mLayoutGenerateBinding.numberPickerGenerateSymbolsMin.getLeft();
                final int maxLeft = Math.max(Math.max(Math.max(lengthLeft, letterLeft), numberLeft), symbolLeft);

                if (maxLeft == 0)
                    return;

                MarginSetter marginSetter = (numberPicker, marginLeft) -> {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) numberPicker.getLayoutParams();
                    if (params.leftMargin != marginLeft) {
                        params.setMargins(marginLeft, 0, 0, 0);
                        numberPicker.setLayoutParams(params);
                    }
                };

                marginSetter.setMargin(mLayoutGenerateBinding.numberPickerGenerateLength, maxLeft - lengthLeft);
                marginSetter.setMargin(mLayoutGenerateBinding.numberPickerGenerateLettersMin, maxLeft - letterLeft);
                marginSetter.setMargin(mLayoutGenerateBinding.numberPickerGenerateNumbersMin, maxLeft - numberLeft);
                marginSetter.setMargin(mLayoutGenerateBinding.numberPickerGenerateSymbolsMin, maxLeft - symbolLeft);

                mActivityMainBinding.viewPager2.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });

        mAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, mPasswordNames);
        mActivityMainBinding.viewPager2.setAdapter(new ViewPager2Adapter(savedInstanceState));
        new TabLayoutMediator(mActivityMainBinding.tabLayout, mActivityMainBinding.viewPager2,
                (tab, position) -> {
                    switch (position) {
                        case 0:
                            tab.setText("New");
                            break;
                        case 1:
                            tab.setText("Read");
                            break;
                        case 2:
                            tab.setText("Generate");
                            break;
                    }
                }).attach();
        setContentView(mActivityMainBinding.getRoot());
        mActivityMainBinding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0:
                        mLayoutNewBinding.editTextNewFilename.requestFocus();
                        break;
                    case 1:
                        if (mLayoutReadBinding.listView.getCheckedItemCount() == 1)
                            mLayoutReadBinding.editTextReadMasterPassword.requestFocus();
                        break;
                    case 2:
                        mLayoutGenerateBinding.numberPickerGenerateLength.requestFocus();
                        break;
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });

        mActivityResultImport = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            Uri source;
            Cursor cursor;
            String filename;
            List<Pair<Uri, File>> overwriteTasks = new ArrayList<>();
            AtomicInteger filesCopied = new AtomicInteger(), skipped = new AtomicInteger(), unknown = new AtomicInteger(), total = new AtomicInteger();
            for (int i = 0; i < uris.size(); ++i) {
                source = uris.get(i);
                filename = "";
                if (Objects.equals(source.getScheme(), "content")) {
                    cursor = getContentResolver().query(source, null, null, null, null);
                    if (cursor != null) {
                        cursor.moveToFirst();
                        filename = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                        cursor.close();
                    }
                } else
                    filename = source.getLastPathSegment();
                if (filename != null) {
                    if (!filename.endsWith(".pwd")) {
                        unknown.incrementAndGet();
                        total.incrementAndGet();
                        continue;
                    }
                    File destination = new File(getFilesDir(), filename);
                    if (destination.exists()) {
                        overwriteTasks.add(new Pair<>(source, destination));
                    } else {
                        try {
                            copy(source, destination);
                            filesCopied.incrementAndGet();
                            mPasswordNames.remove(NO_PASSWORDS);
                            mPasswordNames.add(filename.replace(".pwd", ""));
                        } catch (IOException ioe) {

                        }
                        total.incrementAndGet();
                    }
                } else
                    total.incrementAndGet();
            }
            if (overwriteTasks.isEmpty()) {
                sumUpImportExport(uris.size(), filesCopied.get(), unknown.get(), skipped.get(), "import");
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(MainActivity.this, mAppTheme)).setTitle("Confirm overwrite file");
                View dialogView = getLayoutInflater().inflate(R.layout.alertdialog_mass_overwrite, null);
                LinearLayout linearLayout = dialogView.findViewById(R.id.linearLayout_filesToOverwrite);
                dialogView.findViewById(R.id.button_checkAll).setOnClickListener(v -> {
                    for (int i = 0; i < linearLayout.getChildCount(); ++i) {
                        View child = linearLayout.getChildAt(i);
                        if (child instanceof CheckBox)
                            ((CheckBox) child).setChecked(true);
                    }
                });
                dialogView.findViewById(R.id.button_uncheckAll).setOnClickListener(v -> {
                    for (int i = 0; i < linearLayout.getChildCount(); ++i) {
                        View child = linearLayout.getChildAt(i);
                        if (child instanceof CheckBox)
                            ((CheckBox) child).setChecked(false);
                    }
                });

                for (Pair<Uri, File> overwriteTask : overwriteTasks) {
                    String fileToOverwrite = overwriteTask.second.getName();
                    CheckBox checkBox = new CheckBox(getApplicationContext());
                    checkBox.setText(fileToOverwrite.replace(".pwd", ""));
                    checkBox.setTag(overwriteTask);
                    linearLayout.addView(checkBox);
                }

                builder.setView(dialogView);
                builder.setPositiveButton("OK", (dialog, which) -> {
                    for (int i = 0; i < linearLayout.getChildCount(); ++i) {
                        View child = linearLayout.getChildAt(i);
                        if (child instanceof CheckBox) {
                            CheckBox checkBox = (CheckBox) child;
                            if (checkBox.isChecked()) {
                                Pair<Uri, File> overwriteTask = (Pair<Uri, File>) checkBox.getTag();
                                try {
                                    copy(overwriteTask.first, overwriteTask.second);
                                    filesCopied.incrementAndGet();
                                } catch (IOException ioe) {

                                }
                                total.incrementAndGet();
                            } else {
                                skipped.incrementAndGet();
                            }
                        }
                    }
                    total.incrementAndGet();
                    dialog.dismiss();
                });
                builder.setNegativeButton("Cancel", (dialog, which) -> {
                    skipped.addAndGet(overwriteTasks.size());
                    total.addAndGet(overwriteTasks.size());
                    dialog.dismiss();
                });
                builder.setOnDismissListener(dialog -> sumUpImportExport(uris.size(), filesCopied.get(), unknown.get(), skipped.get(), "import"));
                builder.show();
            }
        });

        mActivityResultExport = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), treeUri -> {
            if (treeUri == null) {
                return;
            }

            DocumentFile root = DocumentFile.fromTreeUri(getApplicationContext(), treeUri);
            if (root == null) {
                return;
            }

            DocumentFile destinationDir = root.findFile("passwords");
            if (destinationDir == null) {
                root.createDirectory("passwords");
                destinationDir = root.findFile("passwords");
            } else if (destinationDir.isFile()) {
                Toast.makeText(getApplicationContext(), "Can't export to " + destinationDir.getName() + ": it's an existing file.", Toast.LENGTH_LONG).show();
                return;
            }
            DocumentFile destination;
            AtomicInteger filesCopied = new AtomicInteger(), unknown = new AtomicInteger(), skipped = new AtomicInteger(), total = new AtomicInteger();
            List<Pair<File, Uri>> overwriteTasks = new ArrayList<>();
            for (String filename : mPasswordNames) {
                destination = destinationDir.findFile(filename + ".pwd");
                if (destination == null) {
                    destination = destinationDir.createFile("*/*", filename + ".pwd");
                    try {
                        copy(new File(getFilesDir(), filename + ".pwd"), destination.getUri());
                        filesCopied.incrementAndGet();
                    } catch (IOException ioe) {

                    }
                    total.incrementAndGet();
                } else {
                    overwriteTasks.add(new Pair<>(new File(getFilesDir(), filename + ".pwd"), destination.getUri()));
                }
            }
            if (overwriteTasks.isEmpty()) {
                sumUpImportExport(mPasswordNames.size(), filesCopied.get(), unknown.get(), skipped.get(), "export");
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(MainActivity.this, mAppTheme)).setTitle("Confirm overwrite file");
                View dialogView = getLayoutInflater().inflate(R.layout.alertdialog_mass_overwrite, null);
                LinearLayout linearLayout = dialogView.findViewById(R.id.linearLayout_filesToOverwrite);
                dialogView.findViewById(R.id.button_checkAll).setOnClickListener(v -> {
                    for (int i = 0; i < linearLayout.getChildCount(); ++i) {
                        View child = linearLayout.getChildAt(i);
                        if (child instanceof CheckBox)
                            ((CheckBox) child).setChecked(true);
                    }
                });
                dialogView.findViewById(R.id.button_uncheckAll).setOnClickListener(v -> {
                    for (int i = 0; i < linearLayout.getChildCount(); ++i) {
                        View child = linearLayout.getChildAt(i);
                        if (child instanceof CheckBox)
                            ((CheckBox) child).setChecked(false);
                    }
                });

                for (Pair<File, Uri> overwriteTask : overwriteTasks) {
                    String fileToOverwrite = overwriteTask.first.getName();
                    CheckBox checkBox = new CheckBox(getApplicationContext());
                    checkBox.setText(fileToOverwrite.replace(".pwd", ""));
                    checkBox.setTag(overwriteTask);
                    linearLayout.addView(checkBox);
                }

                builder.setView(dialogView);
                builder.setPositiveButton("OK", (dialog, which) -> {
                    for (int i = 0; i < linearLayout.getChildCount(); ++i) {
                        View child = linearLayout.getChildAt(i);
                        if (child instanceof CheckBox) {
                            CheckBox checkBox = (CheckBox) child;
                            if (checkBox.isChecked()) {
                                Pair<File, Uri> overwriteTask = (Pair<File, Uri>) checkBox.getTag();
                                try {
                                    copy(overwriteTask.first, overwriteTask.second);
                                    filesCopied.incrementAndGet();
                                } catch (IOException ioe) {

                                }
                                total.incrementAndGet();
                            } else {
                                skipped.incrementAndGet();
                            }
                        }
                    }
                    total.incrementAndGet();
                    dialog.dismiss();
                });
                builder.setNegativeButton("Cancel", (dialog, which) -> {
                    skipped.addAndGet(overwriteTasks.size());
                    total.addAndGet(overwriteTasks.size());
                    dialog.dismiss();
                });
                builder.setOnDismissListener(dialog -> sumUpImportExport(mPasswordNames.size(), filesCopied.get(), unknown.get(), skipped.get(), "export"));
                builder.show();
            }
        });

        mActivityResultExportSingle = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), treeUri -> {
            if (treeUri == null) {
                return;
            }

            DocumentFile root = DocumentFile.fromTreeUri(getApplicationContext(), treeUri);
            if (root == null) {
                return;
            }

            DocumentFile destinationDir = root.findFile("passwords");
            if (destinationDir == null) {
                root.createDirectory("passwords");
                destinationDir = root.findFile("passwords");
            } else if (destinationDir.isFile()) {
                Toast.makeText(getApplicationContext(), "Can't export to " + destinationDir.getName() + ": it's an existing file.", Toast.LENGTH_LONG).show();
                return;
            }
            DocumentFile destination = destinationDir.findFile(mFileToExport + ".pwd");
            if (destination == null) {
                destination = destinationDir.createFile("*/*", mFileToExport + ".pwd");
                try {
                    copy(new File(getFilesDir(), mFileToExport + ".pwd"), destination.getUri());
                    Toast.makeText(getApplicationContext(), "Successfully exported " + mFileToExport, Toast.LENGTH_LONG).show();
                } catch (IOException ioe) {
                    Toast.makeText(getApplicationContext(), "Error exporting " + mFileToExport + ": " + ioe.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(MainActivity.this, mAppTheme)).setTitle("Confirm overwrite file");
                builder.setMessage("A password named " + mFileToExport.replace(".pwd", "") + " already exists. Do you want to overwrite it?");
                Uri finalDestination = destination.getUri();
                builder.setPositiveButton("Yes", (dialog, which) -> {
                    try {
                        copy(new File(getFilesDir(), mFileToExport + ".pwd"), finalDestination);
                        Toast.makeText(getApplicationContext(), "Successfully exported " + mFileToExport, Toast.LENGTH_LONG).show();
                    } catch (IOException ioe) {
                        Toast.makeText(getApplicationContext(), "Error exporting " + mFileToExport + ": " + ioe.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    dialog.dismiss();
                });
                builder.setNegativeButton("No", (dialog, which) -> dialog.dismiss());
                builder.show();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        super.onCreateOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_log).setEnabled(mLastLogMessage != null && !mLastLogMessage.isEmpty());
        menu.findItem(R.id.action_dark_theme).setChecked(mDarkTheme);
        menu.findItem(R.id.action_confirm_passwords).setChecked(mConfirmPasswords);
        menu.findItem(R.id.action_show_passwords).setChecked(mShowPasswords);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_import) {
            String[] mimeTypes = { "*/*" };
            mActivityResultImport.launch(mimeTypes);
            return true;
        } else if (itemId == R.id.action_export) {
            Uri initialUri = Uri.parse(Environment.getExternalStorageDirectory().toURI().toString());
            mActivityResultExport.launch(initialUri);
            return true;
        } else if (itemId == R.id.action_log) {
            AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(MainActivity.this, mAppTheme)).setTitle("Last log message");
            builder.setMessage(mLastLogMessage);
            builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
            builder.show();
            return true;
        } else if (itemId == R.id.action_dark_theme) {
            boolean isDark = !item.isChecked();
            mAppTheme = isDark ? R.style.AppThemeDark : R.style.AppThemeLight;
            item.setChecked(isDark);
            getPreferences(MODE_PRIVATE).edit().putBoolean("dark_theme", isDark).apply();
            recreate();
            return true;
        } else if (itemId == R.id.action_confirm_passwords) {
            mConfirmPasswords = !item.isChecked();
            item.setChecked(mConfirmPasswords);
            setConfirmPasswordsVisible(mConfirmPasswords);
            getPreferences(MODE_PRIVATE).edit().putBoolean("confirm_passwords", mConfirmPasswords).apply();

            // Keep the menu open
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
                    return false;
                }

                @Override
                public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                    return false;
                }
            });
            return false;
        } else if (itemId == R.id.action_show_passwords) {
            mShowPasswords = !item.isChecked();
            item.setChecked(mShowPasswords);
            setShowPasswords(mShowPasswords);
            getPreferences(MODE_PRIVATE).edit().putBoolean("show_passwords", mShowPasswords).apply();

            // Keep the menu open
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
                    return false;
                }

                @Override
                public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                    return false;
                }
            });
            return false;
        }
        return super.onOptionsItemSelected(item);
    }

    void setConfirmPasswordsVisible(boolean visible) {
        ConstraintSet constraints = new ConstraintSet();
        constraints.clone(mLayoutNewBinding.constrainLayout);
        if (visible) {
            constraints.connect(R.id.textView_new_filename,        ConstraintSet.END,   R.id.textView_new_passwordToStoreConfirm, ConstraintSet.END);
            constraints.connect(R.id.editText_new_filename,        ConstraintSet.START, R.id.editText_new_passwordToStoreConfirm, ConstraintSet.START);
            constraints.connect(R.id.textView_new_passwordToStore, ConstraintSet.END,   R.id.textView_new_passwordToStoreConfirm, ConstraintSet.END);
            constraints.connect(R.id.editText_new_passwordToStore, ConstraintSet.START, R.id.editText_new_passwordToStoreConfirm, ConstraintSet.START);
            constraints.connect(R.id.textView_new_masterPassword,  ConstraintSet.END,   R.id.textView_new_passwordToStoreConfirm, ConstraintSet.END);
            constraints.connect(R.id.editText_new_masterPassword,  ConstraintSet.START, R.id.editText_new_passwordToStoreConfirm, ConstraintSet.START);
            constraints.connect(R.id.editText_new_masterPassword,  ConstraintSet.TOP,   R.id.editText_new_passwordToStoreConfirm, ConstraintSet.BOTTOM);
            constraints.connect(R.id.button_new_storePassword,     ConstraintSet.TOP,   R.id.editText_new_masterPasswordConfirm,  ConstraintSet.BOTTOM);
        }
        else {
            constraints.clear(R.id.textView_new_passwordToStore, ConstraintSet.END);
            constraints.connect(R.id.textView_new_passwordToStore, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, getResources().getDisplayMetrics()));

            constraints.connect(R.id.textView_new_filename,        ConstraintSet.END,   R.id.textView_new_passwordToStore, ConstraintSet.END);
            constraints.connect(R.id.editText_new_filename,        ConstraintSet.START, R.id.editText_new_passwordToStore, ConstraintSet.START);
            constraints.connect(R.id.editText_new_passwordToStore, ConstraintSet.START, R.id.textView_new_passwordToStore, ConstraintSet.END);
            constraints.connect(R.id.textView_new_masterPassword,  ConstraintSet.END,   R.id.textView_new_passwordToStore, ConstraintSet.END);
            constraints.connect(R.id.editText_new_masterPassword,  ConstraintSet.START, R.id.editText_new_passwordToStore, ConstraintSet.START);
            constraints.connect(R.id.editText_new_masterPassword,  ConstraintSet.TOP,   R.id.editText_new_passwordToStore, ConstraintSet.BOTTOM);
            constraints.connect(R.id.button_new_storePassword,     ConstraintSet.TOP,   R.id.editText_new_masterPassword,  ConstraintSet.BOTTOM);
        }
        constraints.applyTo(mLayoutNewBinding.constrainLayout);

        final int state = visible ? View.VISIBLE : View.GONE;
        mLayoutNewBinding.textViewNewPasswordToStoreConfirm.setVisibility(state);
        mLayoutNewBinding.editTextNewPasswordToStoreConfirm.setVisibility(state);
        mLayoutNewBinding.textViewNewMasterPasswordConfirm.setVisibility(state);
        mLayoutNewBinding.editTextNewMasterPasswordConfirm.setVisibility(state);
    }

    void setShowPasswords(boolean show) {
        final int state = show ?
                EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
                EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
        mLayoutNewBinding.editTextNewPasswordToStore.setInputType(state);
        mLayoutNewBinding.editTextNewPasswordToStoreConfirm.setInputType(state);
        mLayoutNewBinding.editTextNewMasterPassword.setInputType(state);
        mLayoutNewBinding.editTextNewMasterPasswordConfirm.setInputType(state);
        mLayoutReadBinding.editTextReadMasterPassword.setInputType(state);
    }

    void copy(File source, Uri destination) throws IOException {
        InputStream is = new FileInputStream(source);
        OutputStream os = getContentResolver().openOutputStream(destination);
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) > 0)
            os.write(buffer, 0, length);
        is.close();
        os.close();
    }

    void copy(Uri source, File destination) throws IOException {
        InputStream is = getContentResolver().openInputStream(source);
        OutputStream os = new FileOutputStream(destination);
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) > 0)
            os.write(buffer, 0, length);
        is.close();
        os.close();
    }

    void sumUpImportExport(int filesToCopy, int filesCopied, int unknown, int skipped, String copyDirection) {
        Collections.sort(mPasswordNames, String.CASE_INSENSITIVE_ORDER);
        mAdapter.notifyDataSetChanged();
        StringBuilder messageBuilder = new StringBuilder();
        if (filesCopied > 0)
            messageBuilder.append("Successfully ").append(copyDirection).append("ed ").append(filesCopied).append(" password(s).\n");
        if (skipped > 0)
            messageBuilder.append("Chose not to ").append(copyDirection).append(" ").append(skipped).append(" password(s).\n");
        if (unknown > 0)
            messageBuilder.append(" ").append(unknown).append(" file(s) with unknown type skipped.\n");
        int failed = filesToCopy - filesCopied - unknown - skipped;
        if (failed > 0)
            messageBuilder.append(" ").append(failed).append(" file(s) couldn't be ").append(copyDirection).append("ed.\n");
        final String message = messageBuilder.toString().trim();
        mLastLogMessage = message;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    public class ViewPager2Adapter extends RecyclerView.Adapter<ViewPager2Adapter.ViewHolder> {

        Bundle mSavedInstanceState;
        char[] mCodedPassword = new char[0];
        String mDecodedPasswordCache = "", mGeneratedPasswordCache = "";
        final TextWatcher mDisableManualEditingDecode = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!s.toString().equals(mDecodedPasswordCache))
                    s.replace(0, s.length(), mDecodedPasswordCache);
            }
        };
        final TextWatcher mDisableManualEditingGenerate = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!s.toString().equals(mGeneratedPasswordCache))
                    s.replace(0, s.length(), mGeneratedPasswordCache);
            }
        };

        void generatePassword() {
            int letterCount = mLayoutGenerateBinding.numberPickerGenerateLettersMin.getValue();
            int numberCount = mLayoutGenerateBinding.numberPickerGenerateNumbersMin.getValue();
            int symbolCount = mLayoutGenerateBinding.numberPickerGenerateSymbolsMin.getValue();
            int randomCharacters = mLayoutGenerateBinding.numberPickerGenerateLength.getValue()
                    - letterCount
                    - numberCount
                    - symbolCount;

            final int maxLetters = mLayoutGenerateBinding.numberPickerGenerateLettersMax.getValue();
            final int maxNumbers = mLayoutGenerateBinding.numberPickerGenerateNumbersMax.getValue();
            final int maxSymbols = mLayoutGenerateBinding.numberPickerGenerateSymbolsMax.getValue();

            if (maxLetters + maxNumbers + maxSymbols < mLayoutGenerateBinding.numberPickerGenerateLength.getValue() ||
                    randomCharacters < 0 ||
                    letterCount > maxLetters ||
                    numberCount > maxNumbers ||
                    symbolCount > maxSymbols ||
                    maxLetters + maxNumbers + maxSymbols == 0) {
                mLayoutGenerateBinding.editTextGenerateGeneratedPassword.removeTextChangedListener(mDisableManualEditingGenerate);
                mLayoutGenerateBinding.editTextGenerateGeneratedPassword.setText(mGeneratedPasswordCache = "");
                mLayoutGenerateBinding.editTextGenerateGeneratedPassword.addTextChangedListener(mDisableManualEditingGenerate);
                return;
            }

            Random r = new Random();
            while (randomCharacters > 0) {
                int characterTypes = 0;
                final boolean[] includedTypesMask = new boolean[3];
                final int letterId = 0;
                final int numberId = 1;
                final int symbolId = 2;

                includedTypesMask[letterId] = (letterCount < maxLetters);
                if (includedTypesMask[letterId])
                    ++characterTypes;

                includedTypesMask[numberId] = (numberCount < maxNumbers);
                if (includedTypesMask[numberId])
                    ++characterTypes;

                includedTypesMask[symbolId] = (symbolCount < maxSymbols);
                if (includedTypesMask[symbolId])
                    ++characterTypes;

                int characterType = r.nextInt(characterTypes);
                for (int id = 0; id < 3; ++id) {
                    if (includedTypesMask[id]) {
                        if ((characterType == 0)) {
                            characterType = id;
                            break;
                        }
                        --characterType;
                    }
                }

                switch (characterType) {
                    case letterId: ++letterCount; break;
                    case numberId: ++numberCount; break;
                    case symbolId: ++symbolCount; break;
                    default: break;
                }

                --randomCharacters;
            }

            /*
             * Letters: 65-90 (26), 97-122 (26), total: 52
             * Numbers: 48-57 (10), total: 10
             * Symbols: 33-47 (15), 58-64 (7), 91-96 (6), 123-126 (4), total: 32
             */
            StringBuilder passwordBuilder = new StringBuilder();
            for (int i = 0; i < letterCount; ++i) {
                int character = r.nextInt(52);
                if (character >= 26) {
                    passwordBuilder.append((char) (97 + character - 26));
                } else {
                    passwordBuilder.append((char) (65 + character));
                }
            }
            for (int i = 0; i < numberCount; ++i) {
                int character = r.nextInt(10);
                passwordBuilder.append((char) (48 + character));
            }
            for (int i = 0; i < symbolCount; ++i) {
                int character = r.nextInt(32);
                if (character >= 15 + 7 + 6) {
                    passwordBuilder.append((char) (123 + character - (15 + 7 + 6)));
                } else if (character >= 15 + 7) {
                    passwordBuilder.append((char) (91 + character - (15 + 7)));
                } else if (character >= 15) {
                    passwordBuilder.append((char) (58 + character - 15));
                } else {
                    passwordBuilder.append((char) (33 + character ));
                }
            }

            for (int i = passwordBuilder.length() - 1; i > 0; --i) {
                int j = r.nextInt(i + 1);
                if (i != j) {
                    char tmp = passwordBuilder.charAt(i);
                    passwordBuilder.setCharAt(i, passwordBuilder.charAt(j));
                    passwordBuilder.setCharAt(j, tmp);
                }
            }

            mLayoutGenerateBinding.editTextGenerateGeneratedPassword.removeTextChangedListener(mDisableManualEditingGenerate);
            mLayoutGenerateBinding.editTextGenerateGeneratedPassword.setText(mGeneratedPasswordCache = passwordBuilder.toString());
            mLayoutGenerateBinding.editTextGenerateGeneratedPassword.addTextChangedListener(mDisableManualEditingGenerate);
        }

        final NumberPicker.OnValueChangeListener mParametersChangedGenerate = (picker, oldVal, newVal) -> generatePassword();

        ViewPager2Adapter(Bundle savedInstanceState) {
            mSavedInstanceState = savedInstanceState;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
            switch (viewType) {
                case 0:
                    mLayoutNewBinding = LayoutNewBinding.inflate(inflater, parent, false);
                    setConfirmPasswordsVisible(mConfirmPasswords);
                    setShowPasswords(mShowPasswords);
                    mLayoutNewBinding.buttonNewStorePassword.setOnClickListener(v -> {
                        if (mLayoutNewBinding.editTextNewFilename.getText().length() == 0) {
                            Toast.makeText(MainActivity.this, "Empty filename!", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (mLayoutNewBinding.editTextNewPasswordToStore.getText().length() == 0 ||
                            (mConfirmPasswords && mLayoutNewBinding.editTextNewPasswordToStoreConfirm.getText().length() == 0) ||
                            mLayoutNewBinding.editTextNewMasterPassword.getText().length() == 0 ||
                            (mConfirmPasswords && mLayoutNewBinding.editTextNewMasterPasswordConfirm.getText().length() == 0)) {
                            Toast.makeText(MainActivity.this, "Empty password field!", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (mConfirmPasswords &&
                            (!mLayoutNewBinding.editTextNewPasswordToStore.getText().toString().equals(mLayoutNewBinding.editTextNewPasswordToStoreConfirm.getText().toString()) ||
                            !mLayoutNewBinding.editTextNewMasterPassword.getText().toString().equals(mLayoutNewBinding.editTextNewMasterPasswordConfirm.getText().toString()))) {
                            Toast.makeText(MainActivity.this, "Passwords don't match!", Toast.LENGTH_LONG).show();
                            return;
                        }
                        String filename = mLayoutNewBinding.editTextNewFilename.getText().toString();
                        if (!filename.endsWith(".pwd"))
                            filename += ".pwd";
                        File passwordFile = new File(getFilesDir(), filename);
                        if (passwordFile.exists()) {
                            new AlertDialog.Builder(new ContextThemeWrapper(MainActivity.this, mAppTheme))
                                    .setTitle("Confirm overwrite file")
                                    .setMessage("A password named " + filename.replace(".pwd", "") + " already exists. Do you want to overwrite it?")
                                    .setPositiveButton("Yes", (dialog, which) -> {
                                        storePassword(passwordFile);
                                        mPasswordNames.remove(NO_PASSWORDS);
                                        mPasswordNames.add(passwordFile.getName().replace(".pwd", ""));
                                        Collections.sort(mPasswordNames, String.CASE_INSENSITIVE_ORDER);
                                        mAdapter.notifyDataSetChanged();
                                        dialog.dismiss();
                                    })
                                    .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                                    .show();
                        } else {
                            storePassword(passwordFile);
                            mPasswordNames.remove(NO_PASSWORDS);
                            mPasswordNames.add(filename.replace(".pwd", ""));
                            Collections.sort(mPasswordNames, String.CASE_INSENSITIVE_ORDER);
                            mAdapter.notifyDataSetChanged();
                        }
                    });
                    mLayoutNewBinding.editTextNewFilename.requestFocus(); // TODO: should this be here?
                    view = mLayoutNewBinding.getRoot();
                    break;
                case 1:
                    mLayoutReadBinding = LayoutReadBinding.inflate(inflater, parent, false);
                    setShowPasswords(mShowPasswords);
                    mLayoutReadBinding.editTextReadSearch.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                        }

                        @Override
                        public void onTextChanged(CharSequence s, int start, int before, int count) {
                            String selectedPassword = (String) mLayoutReadBinding.listView.getItemAtPosition(mLayoutReadBinding.listView.getCheckedItemPosition());
                            mAdapter.getFilter().filter(s, count1 -> {
                                if (mAdapter.getPosition(selectedPassword) == -1) {
                                    mLayoutReadBinding.listView.clearChoices();
                                    mLayoutReadBinding.editTextReadMasterPassword.setEnabled(false);
                                }
                            });
                        }

                        @Override
                        public void afterTextChanged(Editable s) {

                        }
                    });
                    mLayoutReadBinding.listView.setAdapter(mAdapter);
                    mLayoutReadBinding.listView.setOnItemClickListener(
                            (par, v, pos, id) -> {
                                String filename = ((TextView) v).getText().toString();
                                if (!filename.equals(NO_PASSWORDS)) {
                                    mLayoutReadBinding.editTextReadMasterPassword.setEnabled(true);
                                    mLayoutReadBinding.editTextReadMasterPassword.requestFocus();
                                    ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(mLayoutReadBinding.editTextReadMasterPassword, InputMethodManager.SHOW_IMPLICIT);
                                    try {
                                        BufferedReader reader = new BufferedReader(new FileReader(new File(getFilesDir(), filename + ".pwd")));
                                        mCodedPassword = reader.readLine().toCharArray();
                                        reader.close();
                                        mLayoutReadBinding.editTextReadMasterPassword.setText(mLayoutReadBinding.editTextReadMasterPassword.getText()); // Hopefully triggers afterTextChanged() of TextWatcher
                                    } catch (IOException ioe) {
                                        if (!new File(getFilesDir(), filename + ".pwd").exists()) {
                                            mPasswordNames.remove(filename);
                                            mAdapter.notifyDataSetChanged();
                                            Toast.makeText(MainActivity.this, "Oops, the file you were about to read from (" + filename + ") suddenly vanished!", Toast.LENGTH_LONG).show();
                                        }
                                    }
                                }
                            });
                    mLayoutReadBinding.listView.setOnItemLongClickListener((par, v, pos, id) -> {
                        PopupMenu popupMenu = new PopupMenu(MainActivity.this, v);
                        popupMenu.inflate(R.menu.popup_menu);
                        popupMenu.show();
                        popupMenu.setOnMenuItemClickListener(item -> {
                            String filename = ((TextView) v).getText().toString();
                            int itemId = item.getItemId();
                            if (itemId == R.id.action_rename) {
                                EditText editText = new EditText(MainActivity.this);
                                new AlertDialog.Builder(new ContextThemeWrapper(MainActivity.this, mAppTheme))
                                        .setTitle("Rename password")
                                        .setView(editText)
                                        .setPositiveButton("Rename", (dialog, which) -> {
                                            String newName = editText.getText().toString();
                                            if (!newName.endsWith(".pwd"))
                                                newName += ".pwd";
                                            if (new File(getFilesDir(), filename + ".pwd").renameTo(new File(getFilesDir(), newName))) {
                                                mPasswordNames.remove(filename);
                                                mPasswordNames.add(newName.replace(".pwd", ""));
                                                Collections.sort(mPasswordNames, String.CASE_INSENSITIVE_ORDER);
                                                mAdapter.notifyDataSetChanged();
                                            } else
                                                Toast.makeText(MainActivity.this, "Couldn't rename file!", Toast.LENGTH_LONG).show();
                                            dialog.dismiss();
                                        })
                                        .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                                        .show();
                                return true;
                            } else if (itemId == R.id.action_delete) {
                                new AlertDialog.Builder(new ContextThemeWrapper(MainActivity.this, mAppTheme))
                                        .setTitle("Confirm delete password")
                                        .setMessage("Do you really want to delete " + filename + "?")
                                        .setPositiveButton("Yes", (dialog, which) -> {
                                            if (new File(getFilesDir(), filename + ".pwd").delete()) {
                                                mPasswordNames.remove(filename);
                                                if (mPasswordNames.isEmpty()) {
                                                    mPasswordNames.add(NO_PASSWORDS);
                                                }
                                                mAdapter.notifyDataSetChanged();
                                                if (mPasswordNames.contains(NO_PASSWORDS)) {
                                                    mLayoutReadBinding.listView.setItemChecked(0, false);
                                                    mLayoutReadBinding.editTextReadMasterPassword.setEnabled(false);
                                                }
                                            } else
                                                Toast.makeText(MainActivity.this, "File could not be deleted!", Toast.LENGTH_LONG).show();
                                            dialog.dismiss();
                                        })
                                        .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                                        .show();
                                return true;
                            } else if (itemId == R.id.action_export_single) {
                                mFileToExport = filename;
                                Uri initialUri = Uri.parse(Environment.getExternalStorageDirectory().toURI().toString());
                                mActivityResultExportSingle.launch(initialUri);
                                return true;
                            }
                            return false;
                        });
                        return true;
                    });
                    mLayoutReadBinding.editTextReadMasterPassword.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                        }

                        @Override
                        public void onTextChanged(CharSequence s, int start, int before, int count) {

                        }

                        @Override
                        public void afterTextChanged(Editable s) {
                            if (s.length() == 0) {
                                mLayoutReadBinding.editTextReadDecodedPassword.removeTextChangedListener(mDisableManualEditingDecode);
                                mLayoutReadBinding.editTextReadDecodedPassword.setText(mDecodedPasswordCache = "");
                                mLayoutReadBinding.editTextReadDecodedPassword.addTextChangedListener(mDisableManualEditingDecode);
                                return;
                            }
                            String masterPassword = s.toString();
                            char[] decodedPassword = mCodedPassword.clone();
                            while (masterPassword.length() < decodedPassword.length)
                                masterPassword += masterPassword;
                            for (int i = 0; i < decodedPassword.length; ++i)
                                decodedPassword[i] = (char) (decodedPassword[i] - masterPassword.charAt(i) + CHAR_LOWEST < CHAR_LOWEST ?
                                                             decodedPassword[i] - masterPassword.charAt(i) + CHAR_HIGHEST + 1 :
                                                             decodedPassword[i] - masterPassword.charAt(i) + CHAR_LOWEST);
                            mLayoutReadBinding.editTextReadDecodedPassword.removeTextChangedListener(mDisableManualEditingDecode);
                            mLayoutReadBinding.editTextReadDecodedPassword.setText(mDecodedPasswordCache = String.valueOf(decodedPassword));
                            mLayoutReadBinding.editTextReadDecodedPassword.addTextChangedListener(mDisableManualEditingDecode);
                        }
                    });
                    mLayoutReadBinding.editTextReadDecodedPassword.addTextChangedListener(mDisableManualEditingDecode);
                    view = mLayoutReadBinding.getRoot();
                    break;
                case 2:
                    mLayoutGenerateBinding = LayoutGenerateBinding.inflate(inflater, parent, false);

                    mLayoutGenerateBinding.numberPickerGenerateLength.setMinValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateLength.setMaxValue(20);
                    mLayoutGenerateBinding.numberPickerGenerateLettersMin.setMinValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateLettersMin.setMaxValue(20);
                    mLayoutGenerateBinding.numberPickerGenerateLettersMax.setMinValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateLettersMax.setMaxValue(20);
                    mLayoutGenerateBinding.numberPickerGenerateNumbersMin.setMinValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateNumbersMin.setMaxValue(20);
                    mLayoutGenerateBinding.numberPickerGenerateNumbersMax.setMinValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateNumbersMax.setMaxValue(20);
                    mLayoutGenerateBinding.numberPickerGenerateSymbolsMin.setMinValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateSymbolsMin.setMaxValue(20);
                    mLayoutGenerateBinding.numberPickerGenerateSymbolsMax.setMinValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateSymbolsMax.setMaxValue(20);

                    mLayoutGenerateBinding.numberPickerGenerateLettersMin.setValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateNumbersMin.setValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateSymbolsMin.setValue(0);
                    mLayoutGenerateBinding.numberPickerGenerateLettersMax.setValue(20);
                    mLayoutGenerateBinding.numberPickerGenerateNumbersMax.setValue(20);
                    mLayoutGenerateBinding.numberPickerGenerateSymbolsMax.setValue(20);

                    mLayoutGenerateBinding.linearLayoutGenerateRootLayout.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        final int height = bottom - top;
                        final int oldHeight = oldBottom - oldTop;
                        if (height < oldHeight) {
                            mDefaultSpaceLayoutParams = (LinearLayout.LayoutParams) mLayoutGenerateBinding.spaceGenerateBottomSpace.getLayoutParams();
                            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(mDefaultSpaceLayoutParams);
                            if (oldHeight * params.weight > oldHeight - height) {
                                float weightChange = ((float) (oldHeight - height)) / oldHeight;
                                mLayoutGenerateBinding.linearLayoutGenerateRootLayout.setWeightSum(mLayoutGenerateBinding.linearLayoutGenerateRootLayout.getWeightSum() - weightChange);
                                params.weight -= weightChange;
                            } else {
                                mLayoutGenerateBinding.linearLayoutGenerateRootLayout.setWeightSum(mLayoutGenerateBinding.linearLayoutGenerateRootLayout.getWeightSum() - params.weight);
                                params.weight = 0.0f;
                            }
                            mLayoutGenerateBinding.spaceGenerateBottomSpace.setLayoutParams(params);
                        } else if (height > oldHeight) {
                            if (mDefaultSpaceLayoutParams != null) {
                                mLayoutGenerateBinding.spaceGenerateBottomSpace.setLayoutParams((mDefaultSpaceLayoutParams));
                                mLayoutGenerateBinding.linearLayoutGenerateRootLayout.setWeightSum(1.0f);
                                mDefaultSpaceLayoutParams = null;
                            }
                        }
                    });

                    mLayoutGenerateBinding.numberPickerGenerateLength.setOnValueChangedListener((picker, oldVal, newVal) -> {
                        if (newVal == 0) {
                            mLayoutGenerateBinding.buttonGenerateStorePassword.setEnabled(false);
                            mLayoutGenerateBinding.imageButtonRegeneratePassword.setEnabled(false);
                            mLayoutGenerateBinding.editTextGenerateGeneratedPassword.removeTextChangedListener(mDisableManualEditingGenerate);
                            mLayoutGenerateBinding.editTextGenerateGeneratedPassword.setText(mGeneratedPasswordCache = "");
                            mLayoutGenerateBinding.editTextGenerateGeneratedPassword.addTextChangedListener(mDisableManualEditingGenerate);
                            return;
                        }
                        mLayoutGenerateBinding.buttonGenerateStorePassword.setEnabled(true);
                        mLayoutGenerateBinding.imageButtonRegeneratePassword.setEnabled(true);
                        generatePassword();
                    });
                    mLayoutGenerateBinding.numberPickerGenerateLettersMin.setOnValueChangedListener(mParametersChangedGenerate);
                    mLayoutGenerateBinding.numberPickerGenerateLettersMax.setOnValueChangedListener(mParametersChangedGenerate);
                    mLayoutGenerateBinding.numberPickerGenerateNumbersMin.setOnValueChangedListener(mParametersChangedGenerate);
                    mLayoutGenerateBinding.numberPickerGenerateNumbersMax.setOnValueChangedListener(mParametersChangedGenerate);
                    mLayoutGenerateBinding.numberPickerGenerateSymbolsMin.setOnValueChangedListener(mParametersChangedGenerate);
                    mLayoutGenerateBinding.numberPickerGenerateSymbolsMax.setOnValueChangedListener(mParametersChangedGenerate);
                    mLayoutGenerateBinding.editTextGenerateGeneratedPassword.addTextChangedListener(mDisableManualEditingGenerate);
                    mLayoutGenerateBinding.editTextGenerateGeneratedPassword.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                        }

                        @Override
                        public void onTextChanged(CharSequence s, int start, int before, int count) {

                        }

                        @Override
                        public void afterTextChanged(Editable s) {
                            mLayoutGenerateBinding.buttonGenerateStorePassword.setEnabled(s.length() > 0);
                        }
                    });
                    mLayoutGenerateBinding.imageButtonRegeneratePassword.setOnClickListener(v -> generatePassword());
                    mLayoutGenerateBinding.imageButtonRegeneratePassword.setEnabled(false);
                    mLayoutGenerateBinding.buttonGenerateStorePassword.setOnClickListener(v -> {
                        mActivityMainBinding.tabLayout.selectTab(mActivityMainBinding.tabLayout.getTabAt(0));
                        mLayoutNewBinding.editTextNewFilename.setText("");
                        mLayoutNewBinding.editTextNewPasswordToStore.setText(mLayoutGenerateBinding.editTextGenerateGeneratedPassword.getText());
                        mLayoutNewBinding.editTextNewPasswordToStoreConfirm.setText(mLayoutGenerateBinding.editTextGenerateGeneratedPassword.getText());
                        mLayoutNewBinding.editTextNewMasterPassword.setText("");
                        mLayoutNewBinding.editTextNewMasterPasswordConfirm.setText("");
                    });
                    view = mLayoutGenerateBinding.getRoot();
                    view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {

                    });
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + viewType);
            }
            if (mSavedInstanceState != null) {
                mLayoutNewBinding.editTextNewFilename.setText(mSavedInstanceState.getString("editText_new_filename"));
                mLayoutNewBinding.editTextNewMasterPassword.setText(mSavedInstanceState.getString("editText_new_masterPassword"));
                mLayoutNewBinding.editTextNewMasterPasswordConfirm.setText(mSavedInstanceState.getString("editText_new_masterPasswordConfirm"));
                mLayoutNewBinding.editTextNewPasswordToStore.setText(mSavedInstanceState.getString("editText_new_passwordToStore"));
                mLayoutNewBinding.editTextNewPasswordToStoreConfirm.setText(mSavedInstanceState.getString("editText_new_passwordToStoreConfirm"));
                mLayoutReadBinding.editTextReadSearch.setText(mSavedInstanceState.getString("editText_read_search"));
                mLayoutReadBinding.editTextReadMasterPassword.setText(mSavedInstanceState.getString("editText_read_masterPassword"));
                mLayoutGenerateBinding.numberPickerGenerateLength.setValue(mSavedInstanceState.getInt("numberPicker_generate_length"));
                mLayoutGenerateBinding.numberPickerGenerateLettersMin.setValue(mSavedInstanceState.getInt("numberPicker_generate_lettersMin"));
                mLayoutGenerateBinding.numberPickerGenerateLettersMax.setValue(mSavedInstanceState.getInt("numberPicker_generate_lettersMax"));
                mLayoutGenerateBinding.numberPickerGenerateNumbersMin.setValue(mSavedInstanceState.getInt("numberPicker_generate_numbersMin"));
                mLayoutGenerateBinding.numberPickerGenerateNumbersMax.setValue(mSavedInstanceState.getInt("numberPicker_generate_numbersMax"));
                mLayoutGenerateBinding.numberPickerGenerateSymbolsMin.setValue(mSavedInstanceState.getInt("numberPicker_generate_symbolsMin"));
                mLayoutGenerateBinding.numberPickerGenerateSymbolsMax.setValue(mSavedInstanceState.getInt("numberPicker_generate_symbolsMax"));
            }
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {

        }

        @Override
        public int getItemCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        void storePassword(File passwordFile) {
            try {
                FileWriter writer = new FileWriter(passwordFile);
                char[] passwordToStore = mLayoutNewBinding.editTextNewPasswordToStore.getText().toString().toCharArray();
                String masterPassword = mLayoutNewBinding.editTextNewMasterPassword.getText().toString();
                while (masterPassword.length() < passwordToStore.length)
                    masterPassword += masterPassword;
                for (int i = 0; i < passwordToStore.length; ++i)
                    passwordToStore[i] = (char) (passwordToStore[i] + masterPassword.charAt(i) - CHAR_LOWEST > CHAR_HIGHEST ?
                                                 passwordToStore[i] + masterPassword.charAt(i) - CHAR_HIGHEST - 1 :
                                                 passwordToStore[i] + masterPassword.charAt(i) - CHAR_LOWEST);
                writer.append(String.valueOf(passwordToStore));
                writer.flush();
                writer.close();
                Toast.makeText(MainActivity.this, "Password successfully saved.", Toast.LENGTH_LONG).show();
            } catch (IOException ioe) {
                Toast.makeText(MainActivity.this, "An error occurred, please try again.", Toast.LENGTH_LONG).show();
            }
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
