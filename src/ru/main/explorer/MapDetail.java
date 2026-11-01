package ru.main.explorer;
/** Screen-scale detail policy, independent from exploration state. */
final class MapDetail {
 static int roadZoom(String kind){if(kind==null||kind.isEmpty())return 13;switch(kind){case "motorway":case "trunk":case "primary":case "highway":case "major_road":return 8;case "secondary":return 11;case "tertiary":return 12;case "residential":case "unclassified":case "minor":return 14;case "service":return 16;case "footway":case "path":case "track":case "steps":case "pedestrian":return 17;default:return 14;}}
 static boolean roadVisible(String kind,int zoom){return zoom>=roadZoom(kind);}
 static float roadWidth(int zoom){return zoom<13?.7f:zoom<16?1f:1.5f;}
 static boolean buildingVisible(int zoom,float width,float height){return zoom>=15&&width>=2&&height>=2;}
}
