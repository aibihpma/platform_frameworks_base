/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;


/**
 * An easy adapter that creates views defined in an XML file. You can specify
 * the XML file that defines the appearance of the views.
 */
public abstract class ResourceCursorAdapter extends CursorAdapter {
    private int mLayout;

    private int mDropDownLayout;
    
    private LayoutInflater mInflater;
    
    /**
     * Constructor.
     * 
     * @param context The context where the ListView associated with this
     *            SimpleListItemFactory is running
     * @param layout resource identifier of a layout file that defines the views
     *            for this list item.
     */
    public ResourceCursorAdapter(Context context, int layout, Cursor c) {
        super(context, c);
        mLayout = mDropDownLayout = layout;
        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }
    
    /**
     * Inflates view(s) from the specified XML file.
     * 
     * @see android.widget.CursorAdapter#newView(android.content.Context,
     *      android.database.Cursor, ViewGroup)
     */
    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(mLayout, parent, false);
    }

    @Override
    public View newDropDownView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(mDropDownLayout, parent, false);
    }

    /**
     * <p>Sets the layout resource of the drop down views.</p>
     *
     * @param dropDownLayout the layout resources used to create drop down views
     */
    public void setDropDownViewResource(int dropDownLayout) {
        mDropDownLayout = dropDownLayout;
    }
}
