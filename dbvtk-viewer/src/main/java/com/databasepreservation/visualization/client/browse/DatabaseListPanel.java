package com.databasepreservation.visualization.client.browse;

import com.databasepreservation.visualization.client.ViewerStructure.ViewerDatabase;
import com.databasepreservation.visualization.client.common.lists.DatabaseList;
import com.databasepreservation.visualization.client.main.BreadcrumbPanel;
import com.databasepreservation.visualization.shared.client.Tools.BreadcrumbManager;
import com.databasepreservation.visualization.shared.client.Tools.HistoryManager;
import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.SelectionChangeEvent;

/**
 * @author Bruno Ferreira <bferreira@keep.pt>
 */
public class DatabaseListPanel extends Composite {
  interface DatabaseListPanelUiBinder extends UiBinder<Widget, DatabaseListPanel> {
  }

  private static DatabaseListPanelUiBinder uiBinder = GWT.create(DatabaseListPanelUiBinder.class);

  // public DatabaseListPanel() {
  // Widget rootElement = ourUiBinder.createAndBindUi(this);
  // }

  @UiField(provided = true)
  DatabaseList databaseList;

  @UiField
  BreadcrumbPanel breadcrumb;

  // @UiField(provided = true)
  // SearchPanel dbSearchPanel;

  public DatabaseListPanel() {
    // dbSearchPanel = new SearchPanel(new Filter(), "", "", false, false);

    databaseList = new DatabaseList();
    initWidget(uiBinder.createAndBindUi(this));

    BreadcrumbManager.updateBreadcrumb(breadcrumb, BreadcrumbManager.forDatabases());

    databaseList.getSelectionModel().addSelectionChangeHandler(new SelectionChangeEvent.Handler() {
      @Override
      public void onSelectionChange(SelectionChangeEvent event) {
        ViewerDatabase selected = databaseList.getSelectionModel().getSelectedObject();
        if (selected != null) {
          HistoryManager.gotoDatabase(selected.getUUID());
        }
      }
    });
  }

  /**
   * This method is called immediately after a widget becomes attached to the
   * browser's document.
   */
  @Override
  protected void onLoad() {
    super.onLoad();
    databaseList.getSelectionModel().clear();
  }
}
