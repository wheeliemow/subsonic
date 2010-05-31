/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */

package net.sourceforge.subsonic.androidapp.activity;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ListView;
import android.widget.AbsListView;
import net.sourceforge.subsonic.androidapp.R;
import net.sourceforge.subsonic.androidapp.service.DownloadServiceImpl;
import net.sourceforge.subsonic.androidapp.util.Util;
import net.sourceforge.subsonic.androidapp.util.SackOfViewsAdapter;

import java.util.Arrays;

public class MainActivity extends SubsonicTabActivity {

    private static final int MENU_GROUP_SERVER = 10;
    private static final int MENU_ITEM_SERVER_1 = 101;
    private static final int MENU_ITEM_SERVER_2 = 102;
    private static final int MENU_ITEM_SERVER_3 = 103;
    private static final int MENU_ITEM_OFFLINE = 104;

    private View serverButton;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        PreferenceManager.setDefaultValues(this, R.xml.settings, false);

        startService(new Intent(this, DownloadServiceImpl.class));
        setContentView(R.layout.main);

//        ListView list = (ListView) findViewById(R.id.main_list);
//        View buttons = LayoutInflater.from(this).inflate(R.layout.main_buttons, list, false);

        serverButton = findViewById(R.id.main_select_server);
        serverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                serverButton.showContextMenu();
            }
        });
        registerForContextMenu(serverButton);

        View settingsButton = findViewById(R.id.main_settings);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        View helpButton = findViewById(R.id.main_help);
        helpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, HelpActivity.class));
            }
        });

//        list.setAdapter(new SackOfViewsAdapter(Arrays.asList(serverButton, settingsButton, helpButton)));
//        settingsButton.setLayoutParams(new AbsListView.LayoutParams(100, 100));
//        list.setAdapter(new SackOfViewsAdapter(Arrays.asList(settingsButton)));
//
        String version = Util.getVersion(this);
        if (version != null) {
            TextView versionText = (TextView) findViewById(R.id.main_version);
            versionText.setText("v " + version);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateActiveServer();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, view, menuInfo);

        if (view == serverButton) {

            MenuItem menuItem1 = menu.add(MENU_GROUP_SERVER, MENU_ITEM_SERVER_1, MENU_ITEM_SERVER_1, Util.getServerName(this, 1));
            MenuItem menuItem2 = menu.add(MENU_GROUP_SERVER, MENU_ITEM_SERVER_2, MENU_ITEM_SERVER_2, Util.getServerName(this, 2));
            MenuItem menuItem3 = menu.add(MENU_GROUP_SERVER, MENU_ITEM_SERVER_3, MENU_ITEM_SERVER_3, Util.getServerName(this, 3));
            MenuItem menuItem4 = menu.add(MENU_GROUP_SERVER, MENU_ITEM_OFFLINE, MENU_ITEM_OFFLINE, Util.getServerName(this, 0));
            menu.setGroupCheckable(MENU_GROUP_SERVER, true, true);

            switch (Util.getActiveServer(this)) {
                case 0:
                    menuItem4.setChecked(true);
                    break;
                case 1:
                    menuItem1.setChecked(true);
                    break;
                case 2:
                    menuItem2.setChecked(true);
                    break;
                case 3:
                    menuItem3.setChecked(true);
                    break;
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case MENU_ITEM_SERVER_1:
                Util.setActiveServer(this, 1);
                break;
            case MENU_ITEM_SERVER_2:
                Util.setActiveServer(this, 2);
                break;
            case MENU_ITEM_SERVER_3:
                Util.setActiveServer(this, 3);
                break;
            case MENU_ITEM_OFFLINE:
                Util.setActiveServer(this, 0);
                break;
            default:
                return super.onContextItemSelected(menuItem);
        }
        updateActiveServer();
        return true;
    }

    private void updateActiveServer() {
        int instance = Util.getActiveServer(this);
        String name = Util.getServerName(this, instance);
        TextView serverText = (TextView) findViewById(R.id.main_server);
        serverText.setText(name);

        // TODO
        boolean offline = instance == 0;
//        loadPlaylistButton.setEnabled(!offline);
//        searchButton.setEnabled(!offline);
    }

}