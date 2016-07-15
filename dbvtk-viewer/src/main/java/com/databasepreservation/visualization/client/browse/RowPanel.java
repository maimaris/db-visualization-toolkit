package com.databasepreservation.visualization.client.browse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.roda.core.data.adapter.filter.Filter;
import org.roda.core.data.v2.index.IsIndexed;

import com.databasepreservation.visualization.client.BrowserService;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerCell;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerColumn;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerDatabase;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerForeignKey;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerReference;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerRow;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerSchema;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerTable;
import com.databasepreservation.visualization.client.common.search.SearchPanel;
import com.databasepreservation.visualization.client.common.sidebar.DatabaseSidebar;
import com.databasepreservation.visualization.client.main.BreadcrumbPanel;
import com.databasepreservation.visualization.shared.client.Tools.BreadcrumbManager;
import com.databasepreservation.visualization.shared.client.Tools.FontAwesomeIconManager;
import com.databasepreservation.visualization.shared.client.Tools.HistoryManager;
import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Hyperlink;
import com.google.gwt.user.client.ui.Widget;

/**
 * @author Bruno Ferreira <bferreira@keep.pt>
 */
public class RowPanel extends Composite {
  private static Map<String, RowPanel> instances = new HashMap<>();

  public static RowPanel createInstance(String databaseUUID, String tableUUID, String rowUUID) {
    return new RowPanel(databaseUUID, tableUUID, rowUUID);
  }

  public static RowPanel createInstance(ViewerDatabase database, ViewerTable table, ViewerRow row) {
    return new RowPanel(database, table, row);
  }

  interface DatabasePanelUiBinder extends UiBinder<Widget, RowPanel> {
  }

  private static DatabasePanelUiBinder uiBinder = GWT.create(DatabasePanelUiBinder.class);

  private ViewerDatabase database;
  private ViewerTable table;
  private final String rowUUID;
  private ViewerRow row;

  @UiField
  BreadcrumbPanel breadcrumb;

  @UiField(provided = true)
  SearchPanel dbSearchPanel;

  @UiField(provided = true)
  DatabaseSidebar sidebar;

  @UiField
  HTML content;

  @UiField
  HTML tableName;

  @UiField
  HTML rowID;

  private RowPanel(ViewerDatabase database, ViewerTable table, ViewerRow row) {
    this.rowUUID = row.getUUID();
    dbSearchPanel = new SearchPanel(new Filter(), "", "Search in all tables", false, false);
    sidebar = DatabaseSidebar.getInstance(database.getUUID());

    initWidget(uiBinder.createAndBindUi(this));

    rowID.setHTML(SafeHtmlUtils.fromSafeConstant(FontAwesomeIconManager.getTag(FontAwesomeIconManager.ROW) + " "
      + SafeHtmlUtils.htmlEscape(rowUUID)));
    tableName.setHTML(FontAwesomeIconManager.loading(FontAwesomeIconManager.TABLE));

    BreadcrumbManager.updateBreadcrumb(breadcrumb,
      BreadcrumbManager.loadingRow(database.getUUID(), table.getUUID(), rowUUID));

    this.database = database;
    this.table = table;
    this.row = row;

    init();
  }

  private RowPanel(final String databaseUUID, final String tableUUID, final String rowUUID) {
    this.rowUUID = rowUUID;
    dbSearchPanel = new SearchPanel(new Filter(), "", "Search in all tables", false, false);
    sidebar = DatabaseSidebar.getInstance(databaseUUID);

    initWidget(uiBinder.createAndBindUi(this));

    rowID.setHTML(SafeHtmlUtils.fromSafeConstant(FontAwesomeIconManager.getTag(FontAwesomeIconManager.ROW) + " "
      + SafeHtmlUtils.htmlEscape(rowUUID)));
    tableName.setHTML(FontAwesomeIconManager.loading(FontAwesomeIconManager.TABLE));

    BreadcrumbManager.updateBreadcrumb(breadcrumb, BreadcrumbManager.loadingRow(databaseUUID, tableUUID, rowUUID));

    BrowserService.Util.getInstance().retrieve(ViewerDatabase.class.getName(), databaseUUID,
      new AsyncCallback<IsIndexed>() {
        @Override
        public void onFailure(Throwable caught) {
          throw new RuntimeException(caught);
        }

        @Override
        public void onSuccess(IsIndexed result) {
          database = (ViewerDatabase) result;
          table = database.getMetadata().getTable(tableUUID);
          init();
        }
      });

    BrowserService.Util.getInstance().retrieveRows(ViewerRow.class.getName(), tableUUID, rowUUID,
      new AsyncCallback<IsIndexed>() {
        @Override
        public void onFailure(Throwable caught) {
          throw new RuntimeException(caught);
        }

        @Override
        public void onSuccess(IsIndexed result) {
          row = (ViewerRow) result;
          init();
        }
      });
  }

  private Hyperlink getHyperlink(String display_text, String database_uuid, String table_uuid) {
    Hyperlink link = new Hyperlink(display_text, HistoryManager.linkToTable(database_uuid, table_uuid));
    return link;
  }

  private void init() {
    if (database == null) {
      return;
    }

    // breadcrumb
    BreadcrumbManager.updateBreadcrumb(
      breadcrumb,
      BreadcrumbManager.forRow(database.getMetadata().getName(), database.getUUID(), table.getSchemaName(),
        table.getSchemaUUID(), table.getName(), table.getUUID(), rowUUID));

    tableName.setHTML(FontAwesomeIconManager.loaded(FontAwesomeIconManager.TABLE, table.getName()));

    if (row != null) {
      Set<Integer> columnIndexesContainingForeignKeyRelations = new HashSet<>();

      // get references where this column is source in foreign keys
      for (ViewerForeignKey fk : table.getForeignKeys()) {
        for (ViewerReference viewerReference : fk.getReferences()) {
          columnIndexesContainingForeignKeyRelations.add(viewerReference.getSourceColumnIndex());
        }
      }

      // get references where this column is (at least one of) the target of
      // foreign keys
      for (ViewerSchema viewerSchema : database.getMetadata().getSchemas()) {
        for (ViewerTable viewerTable : viewerSchema.getTables()) {
          for (ViewerForeignKey viewerForeignKey : viewerTable.getForeignKeys()) {
            if (viewerForeignKey.getReferencedTableUUID().equals(table.getUUID())) {
              for (ViewerReference viewerReference : viewerForeignKey.getReferences()) {
                columnIndexesContainingForeignKeyRelations.add(viewerReference.getReferencedColumnIndex());
              }
            }
          }
        }
      }

      // row data
      SafeHtmlBuilder b = new SafeHtmlBuilder();

      for (ViewerColumn column : table.getColumns()) {
        b.append(getCellHTML(column,
          columnIndexesContainingForeignKeyRelations.contains(column.getColumnIndexInEnclosingTable()), table
            .getPrimaryKey().getColumnIndexesInViewerTable().contains(column.getColumnIndexInEnclosingTable())));
      }

      content.setHTML(b.toSafeHtml());
    }
  }

  private SafeHtml getCellHTML(ViewerColumn column, boolean hasForeignKeyRelations, boolean isPrimaryKeyColumn) {
    String label = column.getDisplayName();

    String value = null;
    ViewerCell cell = row.getCells().get(column.getSolrName());
    if (cell != null) {
      if (cell.getValue() != null) {
        value = cell.getValue();
      }
    }

    SafeHtmlBuilder b = new SafeHtmlBuilder();
    b.appendHtmlConstant("<div class=\"field\">");
    if (isPrimaryKeyColumn) {
      b.appendHtmlConstant("<div class=\"label fa-key\">");
    } else {
      b.appendHtmlConstant("<div class=\"label noicon\">");
    }
    b.appendEscaped(label);
    b.appendHtmlConstant("</div>");
    b.appendHtmlConstant("<div class=\"value\">");
    if (value == null) {
      b.appendEscaped("NULL");
    } else {
      b.appendEscaped(value);
    }
    if (hasForeignKeyRelations && value != null) {
      Hyperlink hyperlink = new Hyperlink("Explore related records", HistoryManager.linkToReferences(
        database.getUUID(), table.getUUID(), rowUUID, String.valueOf(column.getColumnIndexInEnclosingTable())));
      hyperlink.addStyleName("related-records-link");
      b.appendHtmlConstant(hyperlink.toString());
    }
    b.appendHtmlConstant("</div>");

    if (hasForeignKeyRelations && value != null) {
      b.appendHtmlConstant("<div class=\"value\">");
      Hyperlink hyperlink = new Hyperlink("Explore related records", HistoryManager.linkToReferences(
        database.getUUID(), table.getUUID(), rowUUID, String.valueOf(column.getColumnIndexInEnclosingTable())));
      hyperlink.addStyleName("related-records-link");
      b.appendHtmlConstant(hyperlink.toString());
      b.appendHtmlConstant("</div>");
    }

    b.appendHtmlConstant("</div>");
    return b.toSafeHtml();
  }
}