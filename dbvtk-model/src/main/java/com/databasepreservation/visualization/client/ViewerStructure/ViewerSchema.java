package com.databasepreservation.visualization.client.ViewerStructure;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Bruno Ferreira <bferreira@keep.pt>
 */
public class ViewerSchema implements Serializable {
  private String uuid;

  private String name;

  private String description;

  private List<ViewerTable> tables;

  private List<ViewerView> views;

  private List<ViewerRoutine> routines;

  // private List<DbvComposedType> userDefinedTypes;

  public ViewerSchema() {
    tables = new ArrayList<>();
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public List<ViewerTable> getTables() {
    return tables;
  }

  public void setTables(List<ViewerTable> tables) {
    this.tables = tables;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getUUID() {
    return uuid;
  }

  public void setUUID(String uuid) {
    this.uuid = uuid;
  }

  public List<ViewerView> getViews() {
    return views;
  }

  public void setViews(List<ViewerView> views) {
    this.views = views;
  }

  public List<ViewerRoutine> getRoutines() {
    return routines;
  }

  public void setRoutines(List<ViewerRoutine> routines) {
    this.routines = routines;
  }
}
