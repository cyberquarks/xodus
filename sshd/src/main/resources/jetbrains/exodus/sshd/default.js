var MAX_ITEMS_TO_PRINT = 1000;

function print(s) {
  out.print(s);
  out.flush();
}

function println(s) {
  if (!s) s = "";
  out.print(s + "\n\r");
  out.flush();
}

function stat() {
  iter(txn.getEntityTypes(), function(type) {
    println(type + ": " + txn.getAll(type).size());
  });
}

function all(type) {
  printEntityIterable(txn.getAll(type));
}

function find(type, propertyName, propertyValue, maxValue) {
  if (maxValue == undefined) {
    printEntityIterable(txn.find(type, propertyName, propertyValue));
  } else {
    printEntityIterable(txn.find(type, propertyName, propertyValue, maxValue));
  }
}

function findStartingWith(type, propertyName, propertyValue) {
  printEntityIterable(txn.findStartingWith(type, propertyName, propertyValue));
}

function printEntityIterable(entityIterable) {
  var iter = entityIterable.iterator();
  var count = 0;
  while (count++ < MAX_ITEMS_TO_PRINT && iter.hasNext()) {
    var item = iter.next();
    printEntity(item);
    println();
  }

  if (iter.hasNext()) {
    println("And more...");
  }

  println("Total " + entityIterable.size());
}

function printEntity(item) {
  println(item.getType() + " " + item.getId());

  iter(item.getPropertyNames(), function(property) {
    println(property + "=[" + item.getProperty(property) + "]");
  });

  iter(item.getLinkNames(), function(link) {
    println(link + "=[" + item.getLink(link) + "]");
  });
}

function iter(iterable, f) {
  var iter = iterable.iterator();
  while (iter.hasNext()) {
    var item = iter.next();
    f(item);
  }
}

function gc(on) {
  var cfg = store.getEnvironment().getEnvironmentConfig();

  if (on !== undefined) {
    cfg.setGcEnabled(on);
  }

  println("GC is [" + cfg.isGcEnabled() + "]");
}

function add(type, props, flush, print) {
  var entity = txn.newEntity(type);

  if (props) {
    for(var key in props){
      if (props.hasOwnProperty(key)) {
        var val = props[key];
        entity.setProperty(key, val);
      }
    }
  }

  if (flush || flush == undefined) {
    txn.flush();
  }
  if (print || print == undefined) {
    printEntity(entity);
  }
}
