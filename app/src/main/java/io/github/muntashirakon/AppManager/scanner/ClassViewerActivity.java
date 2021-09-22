// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner;

// NOTE: Some patterns here are taken from https://github.com/billthefarmer/editor

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.content.ContextCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;

// Copyright 2015 Google, Inc.
public class ClassViewerActivity extends BaseActivity {
    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_CLASS_NAME = "class_name";
    public static final String EXTRA_CLASS_DUMP = "class_dump";

    private static final Pattern SMALI_KEYWORDS = Pattern.compile(
            "\\b(invoke-(virtual(/range|)|direct|static|interface|super|polymorphic|custom)|" +
                    "move(-(result(-wide|-object|)|exception)|(-wide|-object|)(/16|/from16|))|" +
                    "new-(array|instance)|const(-(string(/jumbo|)|" +
                    "class|wide(/16|/32|/high16|))|/4|/16|/high16|ructor|)|private|public|protected|final|static|" +
                    "(add|sub|cmp|mul|div|rem|and|or|xor|shl|shr|ushr)-(int|float|double|long)(/2addr|/lit16|/lit8|)|" +
                    "(neg|not)-(int|long|float|double)|(int|long|float|double|byte)(-to|)-(int|long|float|double|byte)|" +
                    "fill-array-data|filled-new-array(/range|)|([ais](ge|pu)t|return)(-(object|boolean|byte|char|short|wide|void)|)|" +
                    "check-cast|throw|array-length|goto|if-((ge|le|ne|eq|lt|gt)z?))|monitor-(enter|exit)\\b", Pattern.MULTILINE);

    private static final Pattern SMALI_CLASS = Pattern.compile("\\b\\[?L[\\w]+/[^;]+;|\\[?[ZBCSIJFDV]\\b", Pattern.MULTILINE);

    private static final Pattern SMALI_COMMENT = Pattern.compile("#.*$", Pattern.MULTILINE);

    private static final Pattern SMALI_VALUE = Pattern.compile(
            "\\b(\".*\"|-?(0x[0-9a-f]+|[0-9]+))\\b",
            Pattern.MULTILINE);

    private static final Pattern SMALI_LABELS = Pattern.compile("\\b[pv][0-9]+|:[\\w]+|->\\b|",
            Pattern.MULTILINE);

    private static final Pattern KEYWORDS = Pattern.compile
            ("\\b(abstract|and|arguments|as(m|sert|sociativity)?|auto|break|" +
                    "case|catch|chan|char|class|con(st|tinue|venience)|continue|" +
                    "de(bugger|f|fault|fer|in|init|l|lete)|didset|do(ne)?|dynamic" +
                    "(type)?|el(if|se)|enum|esac|eval|ex(cept|ec|plicit|port|" +
                    "tends|tension|tern)|fal(lthrough|se)|fi(nal|nally)?|for|" +
                    "friend|from|func(tion)?|get|global|go(to)?|if|" +
                    "im(plements|port)|in(fix|it|line|out|stanceof|terface|" +
                    "ternal)?|is|lambda|lazy|left|let|local|map|mut(able|ating)|" +
                    "namespace|native|new|nil|none|nonmutating|not|null|" +
                    "operator|optional|or|override|package|pass|postfix|" +
                    "pre(cedence|fix)|print|private|prot(ected|ocol)|public|" +
                    "raise|range|register|required|return|right|select|self|" +
                    "set|signed|sizeof|static|strictfp|struct|subscript|super|" +
                    "switch|synchronized|template|th(en|is|rows?)|transient|" +
                    "true|try|type(alias|def|id|name|of)?|un(ion|owned|signed)|" +
                    "using|var|virtual|void|volatile|weak|wh(ere|ile)|willset|" +
                    "with|yield)\\b", Pattern.MULTILINE);

    private static final Pattern TYPES = Pattern.compile
            ("\\b(j?bool(ean)?|[uj]?(byte|char|double|float|int(eger)?|" +
                    "long|short))\\b", Pattern.MULTILINE);
    private static final Pattern CC_COMMENT = Pattern.compile
            ("//.*$|(\"(?:\\\\[^\"]|\\\\\"|.)*?\")|(?s)/\\*.*?\\*/",
                    Pattern.MULTILINE);
    private static final Pattern CLASS = Pattern.compile
            ("\\b[A-Z][A-Za-z0-9_]+\\b", Pattern.MULTILINE);

    private String classDump;
    private SpannableString formattedContent;
    private boolean isWrapped = true;  // Wrap by default
    private AppCompatEditText container;
    private LinearProgressIndicator mProgressIndicator;
    private String className;
    private final ActivityResultLauncher<String> exportManifest = registerForActivityResult(
            new ActivityResultContracts.CreateDocument(),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                    Objects.requireNonNull(outputStream).write(classDump.getBytes());
                    outputStream.flush();
                    Toast.makeText(this, R.string.saved_successfully, Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(this, R.string.saving_failed, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onAuthenticated(Bundle savedInstanceState) {
        setContentView(R.layout.activity_any_viewer);
        setSupportActionBar(findViewById(R.id.toolbar));
        mProgressIndicator = findViewById(R.id.progress_linear);
        mProgressIndicator.setVisibilityAfterHide(View.GONE);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            CharSequence appName = getIntent().getCharSequenceExtra(EXTRA_APP_NAME);
            className = getIntent().getStringExtra(EXTRA_CLASS_NAME);
            if (className != null) {
                String barName;
                try {
                    barName = className.substring(className.lastIndexOf(".") + 1);
                } catch (Exception e) {
                    barName = className;
                }
                actionBar.setSubtitle(barName);
            }
            if (appName != null) actionBar.setTitle(appName);
            else actionBar.setTitle(R.string.class_viewer);
        }
        classDump = getIntent().getStringExtra(EXTRA_CLASS_DUMP);
        setWrapped();
    }


    private void setWrapped() {
        if (container != null) container.setVisibility(View.GONE);
        if (isWrapped) container = findViewById(R.id.any_view_wrapped);
        else container = findViewById(R.id.any_view);
        container.setVisibility(View.VISIBLE);
        container.setKeyListener(null);
        container.setTextColor(ContextCompat.getColor(this, R.color.dark_orange));
        displaySmaliContent();
        isWrapped = !isWrapped;
    }

    private void displayContent() {
        mProgressIndicator.show();
        final int typeClassColor = ContextCompat.getColor(this, R.color.ocean_blue);
        final int keywordsColor = ContextCompat.getColor(this, R.color.dark_orange);
        final int commentColor = ContextCompat.getColor(this, R.color.textColorSecondary);
        new Thread(() -> {
            if (formattedContent == null) {
                formattedContent = new SpannableString(classDump);
                Matcher matcher = TYPES.matcher(classDump);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(typeClassColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                matcher.usePattern(CLASS);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(typeClassColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                matcher.usePattern(KEYWORDS);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(keywordsColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                matcher.usePattern(CC_COMMENT);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(commentColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            runOnUiThread(() -> {
                container.setText(formattedContent);
                mProgressIndicator.hide();
            });
        }).start();
    }

    private void displaySmaliContent() {
        mProgressIndicator.show();
        final int typeClassColor = ContextCompat.getColor(this, R.color.ocean_blue);
        final int keywordsColor = ContextCompat.getColor(this, R.color.purple_y);
        final int commentColor = ContextCompat.getColor(this, R.color.textColorSecondary);
        final int valueColor = ContextCompat.getColor(this, R.color.redder_than_you);
        final int labelColor = ContextCompat.getColor(this, R.color.green_mountain);
        new Thread(() -> {
            if (formattedContent == null) {
                formattedContent = new SpannableString(classDump);
                Matcher matcher = SMALI_VALUE.matcher(classDump);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(valueColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                matcher.usePattern(SMALI_LABELS);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(labelColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                matcher.usePattern(SMALI_CLASS);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(typeClassColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                matcher.usePattern(SMALI_KEYWORDS);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(keywordsColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    formattedContent.setSpan(new StyleSpan(Typeface.BOLD), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                matcher.usePattern(SMALI_COMMENT);
                while (matcher.find()) {
                    formattedContent.setSpan(new ForegroundColorSpan(commentColor), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    formattedContent.setSpan(new StyleSpan(Typeface.ITALIC), matcher.start(),
                            matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            runOnUiThread(() -> {
                container.setText(formattedContent);
                mProgressIndicator.hide();
            });
        }).start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_any_viewer_actions, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.action_wrap) {
            setWrapped();
        } else if (id == R.id.action_save) {
            String fileName = className + ".java";
            exportManifest.launch(fileName);
        } else return super.onOptionsItemSelected(item);
        return true;
    }
}
