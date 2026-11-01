package ru.main.explorer;
/** Runs without Android classes or an installed resource resolver. */
public final class MessageFallbackTest {
 public static void main(String[] args){
  String[] codes={"mock_gps_location","invalid_coordinates","poor_gps_accuracy","stale_location","invalid_gps_time","gps_gap_waiting_for_the_next_location","too_fast_gps_jump"};
  for(String code:codes){String message=Messages.text(code);if(message.equals(code)||message.isEmpty()||message.matches(".*[А-Яа-яЁё].*"))throw new AssertionError(code);}
  if(!Messages.format("map_section_progress",4,10,3).equals("Map: section 4 / 10 · saved 3"))throw new AssertionError("English pure-Java formatting");
  try{new RegionPlan(90,0,1000);throw new AssertionError("Invalid region accepted");}catch(IllegalArgumentException expected){if(!expected.getMessage().startsWith("Area must be within"))throw new AssertionError(expected);}
  System.out.println("PASS: 9 pure message fallback checks");
 }
}
