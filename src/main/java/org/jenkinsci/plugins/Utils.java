package org.jenkinsci.plugins;

import java.io.File;

public class Utils {
    public static boolean isEmpty(String str){
        if (str == null || str.isEmpty()){
            return true;
        }
        return false;
    }

    public static boolean fileExists(String file){
        File f = new File(file);
        if(f.exists() && !f.isDirectory()) {
            return true;
        }
        return false;
    }
}
