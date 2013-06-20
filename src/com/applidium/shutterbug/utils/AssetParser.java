package com.applidium.shutterbug.utils;

import android.content.res.AssetManager;
import android.net.Uri;

public class AssetParser {

    public static final String SCHEME_ASSET = "asset";
    
    /**
     * Parses the relative path of an asset uri specified as <i>asset:///path_from_assets_dir/asset_file.png</i> into
     * <i>path_from_assets_dir/asset_file.png</i> so that it can be opened via {@link AssetManager#open(String)}
     * @param assetUri
     * @return asset relative path
     */
    public static String relativePathForAssetUri( String assetUri ) {
        Uri uri = Uri.parse( assetUri );
        String absPath = uri.getPath();
        
        if ( SCHEME_ASSET.equals( uri.getScheme() ) && absPath.length() > 1 ) {
            return absPath.substring( 1, absPath.length() );
        }
        
        return null;
    }
    
    public static boolean isAssetUri( String uriToTest ) {
        return uriToTest != null && SCHEME_ASSET.equals( Uri.parse( uriToTest ).getScheme() );
    }
}
