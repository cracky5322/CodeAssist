package com.tyron.layoutpreview.parser;

import static com.tyron.layoutpreview.parser.WrapperUtils.addEnumProcessors;
import static com.tyron.layoutpreview.parser.WrapperUtils.getMethod;
import static com.tyron.layoutpreview.parser.WrapperUtils.getParameters;
import static com.tyron.layoutpreview.parser.WrapperUtils.invokeMethod;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.ColorResourceProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.EnumProcessor;
import com.flipkart.android.proteus.processor.NumberAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;
import com.tyron.layoutpreview.view.CustomViewWrapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class CustomViewParser extends ViewTypeParser<View> {

    private CustomView mCustomView;

    public CustomViewParser(CustomView customView) {
        mCustomView = customView;
    }

    @NonNull
    @Override
    public String getType() {
        return mCustomView.getType();
    }

    @Nullable
    @Override
    public String getParentType() {
        return mCustomView.getParentType();
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new CustomViewWrapper(context, mCustomView);
    }

    @Override
    protected void addAttributeProcessors() {
        Class<? extends View> clazz = null;
        try {
            clazz = Class.forName(mCustomView.getType())
                    .asSubclass(View.class);
        } catch (ClassNotFoundException e) {
            Log.w("CustomViewParser", "Unknown view class " + mCustomView.getType());
        }

        if (clazz == null) {
            return;
        }

        for (Attribute attribute : mCustomView.getAttributes()) {
            String name = attribute.getXmlName();
            String methodName = attribute.getMethodName();
            String[] parameters = attribute.getParameters();
            int offset = attribute.getXmlParameterOffset();
            String xmlParameter = parameters[offset];
            Class<?>[] parametersClass = getParameters(parameters);
            Object[] objects = new Object[parameters.length];

            Method method = getMethod(clazz, methodName, parametersClass);
            if (method == null) {
                Log.w("CustomViewParser", "Unable to find method " + methodName + " parameters: " + parametersClass);
                continue;
            }
            AttributeProcessor<View> processor = null;

            if (attribute.getFormats().size() == 1 && !attribute.getFormats().contains(Format.ENUM)) {
                if (attribute.getFormats().contains(Format.COLOR)) {
                    addAttributeProcessor(name, new ColorResourceProcessor<View>() {
                        @Override
                        public void setColor(View view, int color) {
                            objects[offset] = color;
                            invokeMethod(view, method, objects);
                        }

                        @Override
                        public void setColor(View view, ColorStateList colors) {

                        }
                    });
                }
            } else if (attribute.getFormats().contains(Format.ENUM)) {
                addEnumProcessors(this, attribute, method, objects);
            }
         }
    }

    private AttributeProcessor<View> getLayoutParamsProcessor(Attribute attribute) {
       if (attribute.getFormats().contains(Format.REFERENCE)) {
           return new StringAttributeProcessor<View>() {
               @Override
               public void setString(View view, String value) {
                   ProteusContext context = (ProteusContext) view.getContext();
                   int id = context.getInflater().getUniqueViewId(value);

               }
           };
       }
       return null;
    }



}